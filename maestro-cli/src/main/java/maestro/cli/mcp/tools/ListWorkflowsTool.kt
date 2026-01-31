package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.util.WorkingDirectory
import maestro.orchestra.yaml.YamlCommandReader
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

object ListWorkflowsTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "list_workflows",
                description = """
                    List all available workflows defined in the project's workflows.yaml manifest.

                    Workflows are named, reusable sequences of Maestro actions (e.g., "login", "navigate_to_profile")
                    that can be executed with the run_workflow tool. This tool discovers workflows by scanning for
                    a workflows.yaml file in the working directory or .maestro/ subdirectory.

                    Returns workflow names, descriptions, required environment variables, and step counts.
                    Use this to discover what shortcuts are available before running them.
                """.trimIndent(),
                inputSchema = Tool.Input(
                    properties = buildJsonObject {},
                    required = emptyList()
                )
            )
        ) { _ ->
            try {
                val manifestFile = findManifest()

                if (manifestFile == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(buildJsonObject {
                            put("found", false)
                            put("message", "No workflows.yaml found. Searched: ${WorkingDirectory.baseDir}/workflows.yaml, ${WorkingDirectory.baseDir}/.maestro/workflows.yaml")
                            put("hint", "Create a workflows.yaml file to define reusable workflows. See run_workflow tool description for the expected format.")
                        }.toString()))
                    )
                }

                val workflows = parseManifest(manifestFile)

                val result = buildJsonObject {
                    put("found", true)
                    put("manifest_path", manifestFile.absolutePath)
                    putJsonArray("workflows") {
                        workflows.forEach { workflow ->
                            addJsonObject {
                                put("name", workflow.name)
                                put("description", workflow.description)
                                put("flow_file", workflow.flowFile)
                                putJsonArray("env_vars") {
                                    workflow.envVars.forEach { add(it) }
                                }
                                put("step_count", workflow.stepCount)
                            }
                        }
                    }
                }

                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to list workflows: ${e.message}")),
                    isError = true
                )
            }
        }
    }

    private fun findManifest(): File? {
        val candidates = listOf(
            WorkingDirectory.resolve("workflows.yaml"),
            WorkingDirectory.resolve(".maestro/workflows.yaml")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseManifest(file: File): List<WorkflowInfo> {
        val mapper = ObjectMapper(YAMLFactory())
        val root = mapper.readValue(file, Map::class.java) as Map<String, Any>
        val workflowsMap = root["workflows"] as? Map<String, Any> ?: return emptyList()

        return workflowsMap.map { (name, value) ->
            val config = value as? Map<String, Any> ?: mapOf()
            val description = config["description"]?.toString() ?: ""
            val flowFile = config["flow_file"]?.toString() ?: ""
            val envVars = (config["env"] as? List<String>) ?: emptyList()

            // Try to count steps from the referenced flow file
            val stepCount = try {
                val resolvedFlow = WorkingDirectory.resolve(flowFile)
                if (resolvedFlow.exists()) {
                    YamlCommandReader.readCommands(resolvedFlow.toPath()).size
                } else {
                    -1
                }
            } catch (e: Exception) {
                -1
            }

            WorkflowInfo(name, description, flowFile, envVars, stepCount)
        }
    }

    private data class WorkflowInfo(
        val name: String,
        val description: String,
        val flowFile: String,
        val envVars: List<String>,
        val stepCount: Int
    )
}
