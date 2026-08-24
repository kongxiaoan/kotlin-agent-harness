package com.attuchengmen.cli

/** 交互式 CLI 接受的一条用户命令。 */
sealed interface CliCommand {
    data class Submit(val content: String) : CliCommand

    data object NewSession : CliCommand

    data object ShowSession : CliCommand

    data object Exit : CliCommand

    data class Invalid(val message: String) : CliCommand
}

/** 将终端单行输入解析为消息或 Runtime 控制命令。 */
object CliCommandParser {
    fun parse(input: String): CliCommand? {
        if (input.isBlank()) return null
        return when (input.trim()) {
            "/new" -> CliCommand.NewSession
            "/session" -> CliCommand.ShowSession
            "/exit" -> CliCommand.Exit
            else -> if (input.trimStart().startsWith('/')) {
                CliCommand.Invalid("unknown command: ${input.trim()}")
            } else {
                CliCommand.Submit(input)
            }
        }
    }
}
