package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.WorkingDirectory
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.util.Env.withDefaultEnvVars
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlinx.coroutines.runBlocking
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

object RunWorkflowTool {

    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_workflow",
                description = """
                    Execute a named workflow from the project's workflows.yaml manifest.

                    Workflows are reusable sequences of Maestro actions (e.g., "login", "navigate_to_profile")
                    that quickly bring the app to a known state. Use list_workflows to discover available workflows.

                    Key features:
                    - Step-level tracking: each step reports its status (completed/warned/skipped/failed)
                    - Rich error output: on failure, returns structured error JSON + screenshot + view hierarchy
                    - Resume support: use from_step to skip already-completed steps after fixing a failure

                    On failure, the response includes three parts:
                    1. JSON with failed step details, completed steps, remaining steps, and recovery suggestions
                    2. Screenshot of the device at the moment of failure
                    3. View hierarchy CSV of the current screen

                    IMPORTANT: Before using from_step to resume, verify the app is in the expected state
                    using inspect_view_hierarchy. The app may have changed since the failure.
                """.trimIndent(),
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to run the workflow on")
                        }
                        putJsonObject("workflow_name") {
                            put("type", "string")
                            put("description", "The name of the workflow to execute (as defined in workflows.yaml)")
                        }
                        putJsonObject("env") {
                            put("type", "object")
                            put("description", "Optional environment variables to inject into the workflow")
                            putJsonObject("additionalProperties") {
                                put("type", "string")
                            }
                        }
                        putJsonObject("from_step") {
                            put("type", "integer")
                            put("description", "Optional step index (0-based) to resume execution from. Steps before this index are skipped. Use after fixing a failure to continue where execution left off.")
                        }
                    },
                    required = listOf("device_id", "workflow_name")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val workflowName = request.arguments["workflow_name"]?.jsonPrimitive?.content
                val envParam = request.arguments["env"]?.jsonObject
                val fromStep = request.arguments["from_step"]?.jsonPrimitive?.intOrNull

                if (deviceId == null || workflowName == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and workflow_name are required")),
                        isError = true
                    )
                }

                // Load workflow from manifest
                val workflow = loadWorkflow(workflowName)
                    ?: return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(buildJsonObject {
                            put("success", false)
                            put("error", "Workflow '$workflowName' not found in workflows.yaml")
                            put("hint", "Use list_workflows to see available workflows")
                        }.toString())),
                        isError = true
                    )

                // Resolve flow file
                val flowFile = WorkingDirectory.resolve(workflow.flowFile)
                if (!flowFile.exists()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Flow file not found: ${flowFile.absolutePath}")),
                        isError = true
                    )
                }

                // Parse environment variables
                val env = envParam?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

                // Execute with step tracking
                val result = sessionManager.newSession(
                    host = null,
                    port = null,
                    driverHostPort = null,
                    deviceId = deviceId,
                    platform = null
                ) { session ->
                    val completedSteps = mutableListOf<JsonObject>()
                    var failedStepJson: JsonObject? = null
                    val stepOffset = fromStep ?: 0
                    val startTime = System.currentTimeMillis()

                    // Parse all commands to get total count
                    val allCommands = YamlCommandReader.readCommands(flowFile.toPath())
                    val totalSteps = allCommands.size

                    // Apply from_step offset
                    val commandsToRun = if (fromStep != null && fromStep > 0 && fromStep < allCommands.size) {
                        allCommands.subList(fromStep, allCommands.size)
                    } else {
                        allCommands
                    }

                    val finalEnv = env
                        .withInjectedShellEnvVars()
                        .withDefaultEnvVars(flowFile, deviceId)
                    val commandsWithEnv = commandsToRun.withEnv(finalEnv)

                    val orchestra = Orchestra(
                        session.maestro,
                        onCommandStart = { _, _ -> },
                        onCommandComplete = { index, cmd ->
                            completedSteps.add(buildJsonObject {
                                put("index", index + stepOffset)
                                put("status", "completed")
                                put("command", cmd.description())
                            })
                        },
                        onCommandFailed = { index, cmd, error ->
                            failedStepJson = buildJsonObject {
                                put("index", index + stepOffset)
                                put("command", cmd.description())
                                put("error", error.message ?: "Unknown error")
                                put("optional", cmd.asCommand()?.optional == true)
                            }
                            Orchestra.ErrorResolution.FAIL
                        },
                        onCommandWarned = { index, cmd ->
                            completedSteps.add(buildJsonObject {
                                put("index", index + stepOffset)
                                put("status", "warned")
                                put("command", cmd.description())
                            })
                        },
                        onCommandSkipped = { index, cmd ->
                            completedSteps.add(buildJsonObject {
                                put("index", index + stepOffset)
                                put("status", "skipped")
                                put("command", cmd.description())
                            })
                        }
                    )

                    val success = runBlocking {
                        orchestra.runFlow(commandsWithEnv)
                    }

                    val durationMs = System.currentTimeMillis() - startTime

                    if (success) {
                        WorkflowResult(
                            json = buildJsonObject {
                                put("success", true)
                                put("workflow", workflowName)
                                put("device_id", deviceId)
                                put("steps_completed", completedSteps.size)
                                put("total_steps", totalSteps)
                                put("duration_ms", durationMs)
                                if (fromStep != null && fromStep > 0) {
                                    put("resumed_from_step", fromStep)
                                }
                                putJsonArray("step_details") {
                                    completedSteps.forEach { add(it) }
                                }
                            }.toString(),
                            screenshotBase64 = null,
                            hierarchyCsv = null,
                            isError = false
                        )
                    } else {
                        // Capture screenshot on failure
                        val screenshotBase64 = try {
                            val buffer = Buffer()
                            session.maestro.takeScreenshot(buffer, true)
                            val pngBytes = buffer.readByteArray()
                            val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                            val jpegOutput = ByteArrayOutputStream()
                            ImageIO.write(pngImage, "JPEG", jpegOutput)
                            Base64.getEncoder().encodeToString(jpegOutput.toByteArray())
                        } catch (e: Exception) {
                            null
                        }

                        // Capture view hierarchy on failure
                        val hierarchyCsv = try {
                            val viewHierarchy = session.maestro.viewHierarchy()
                            ViewHierarchyFormatters.extractCsvOutput(viewHierarchy.root)
                        } catch (e: Exception) {
                            null
                        }

                        // Build remaining steps list
                        val failedIndex = failedStepJson?.get("index")?.jsonPrimitive?.intOrNull ?: -1
                        val remainingSteps = if (failedIndex >= 0 && failedIndex + 1 < totalSteps) {
                            (failedIndex + 1 until totalSteps).map { i ->
                                if (i < allCommands.size) {
                                    buildJsonObject {
                                        put("index", i)
                                        put("command", allCommands[i].description())
                                    }
                                } else null
                            }.filterNotNull()
                        } else {
                            emptyList()
                        }

                        WorkflowResult(
                            json = buildJsonObject {
                                put("success", false)
                                put("workflow", workflowName)
                                put("device_id", deviceId)
                                put("total_steps", totalSteps)
                                put("duration_ms", durationMs)
                                if (fromStep != null && fromStep > 0) {
                                    put("resumed_from_step", fromStep)
                                }
                                failedStepJson?.let { put("failed_step", it) }
                                putJsonArray("completed_steps") {
                                    completedSteps.forEach { add(it) }
                                }
                                putJsonArray("remaining_steps") {
                                    remainingSteps.forEach { add(it) }
                                }
                                putJsonObject("recovery") {
                                    if (failedIndex >= 0) {
                                        put("retry_from_failed", "run_workflow(workflow_name='$workflowName', from_step=$failedIndex)")
                                        if (failedIndex + 1 < totalSteps) {
                                            put("skip_failed", "run_workflow(workflow_name='$workflowName', from_step=${failedIndex + 1})")
                                        }
                                    }
                                    put("full_retry", "run_workflow(workflow_name='$workflowName')")
                                    put("inspect", "inspect_view_hierarchy(device_id='$deviceId')")
                                }
                            }.toString(),
                            screenshotBase64 = screenshotBase64,
                            hierarchyCsv = hierarchyCsv,
                            isError = true
                        )
                    }
                }

                // Build multi-content response
                val content = mutableListOf<PromptMessageContent>(TextContent(result.json))
                if (result.isError && result.screenshotBase64 != null) {
                    content.add(ImageContent(
                        data = result.screenshotBase64,
                        mimeType = "image/jpeg"
                    ))
                }
                if (result.isError && result.hierarchyCsv != null) {
                    content.add(TextContent("--- View Hierarchy ---\n${result.hierarchyCsv}"))
                }

                CallToolResult(content = content, isError = result.isError)
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to run workflow: ${e.message}")),
                    isError = true
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadWorkflow(name: String): WorkflowConfig? {
        val manifestFile = listOf(
            WorkingDirectory.resolve("workflows.yaml"),
            WorkingDirectory.resolve(".maestro/workflows.yaml")
        ).firstOrNull { it.exists() && it.isFile } ?: return null

        val mapper = ObjectMapper(YAMLFactory())
        val root = mapper.readValue(manifestFile, Map::class.java) as Map<String, Any>
        val workflowsMap = root["workflows"] as? Map<String, Any> ?: return null
        val config = workflowsMap[name] as? Map<String, Any> ?: return null

        return WorkflowConfig(
            name = name,
            description = config["description"]?.toString() ?: "",
            flowFile = config["flow_file"]?.toString() ?: "",
            envVars = (config["env"] as? List<String>) ?: emptyList()
        )
    }

    private data class WorkflowConfig(
        val name: String,
        val description: String,
        val flowFile: String,
        val envVars: List<String>
    )

    private data class WorkflowResult(
        val json: String,
        val screenshotBase64: String?,
        val hierarchyCsv: String?,
        val isError: Boolean
    )
}
