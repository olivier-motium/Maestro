package maestro.cli.command

import picocli.CommandLine
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Callable
import maestro.cli.mcp.runMaestroMcpServer
import maestro.cli.util.WorkingDirectory

@CommandLine.Command(
    name = "mcp",
    description = [
        "Starts the Maestro MCP server, exposing Maestro device and automation commands as Model Context Protocol (MCP) tools over STDIO for LLM agents and automation clients."
    ],
)
class McpCommand : Callable<Int> {
    companion object {
        /** Original System.out saved before stdout redirect in main().
         *  Used as the MCP transport output so JSON-RPC goes to real stdout
         *  while all other output (logging, analytics) goes to stderr. */
        @JvmStatic
        var originalStdout: PrintStream = System.out
    }

    @CommandLine.Option(
        names = ["--working-dir"],
        description = ["Base working directory for resolving files"]
    )
    private var workingDir: File? = null

    override fun call(): Int {
        if (workingDir != null) {
            WorkingDirectory.baseDir = workingDir!!.absoluteFile
        }
        runMaestroMcpServer(originalStdout)
        return 0
    }
} 