package com.attuchengmen.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CliCommandParserTest {
    @Test
    fun `normal text becomes a message without trimming its content`() {
        assertEquals(CliCommand.Submit("  hello  "), CliCommandParser.parse("  hello  "))
    }

    @Test
    fun `supported slash commands are parsed explicitly`() {
        assertEquals(CliCommand.NewSession, CliCommandParser.parse("/new"))
        assertEquals(CliCommand.ShowSession, CliCommandParser.parse("/session"))
        assertEquals(CliCommand.Exit, CliCommandParser.parse("/exit"))
    }

    @Test
    fun `blank input is ignored and unknown slash command is rejected`() {
        assertNull(CliCommandParser.parse("   "))
        assertEquals(CliCommand.Invalid("unknown command: /unknown"), CliCommandParser.parse("/unknown"))
    }
}
