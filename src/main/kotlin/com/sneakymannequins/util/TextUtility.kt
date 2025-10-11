package com.sneakymannequins.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage

/**
 * Utility class for text formatting and manipulation.
 * Handles color code conversion and text wrapping for the plugin.
 */
object TextUtility {

    /**
     * Converts a string with legacy color codes to a Component.
     * Automatically disables italic formatting.
     * 
     * @param message The message to convert
     * @return A Component with the formatted text
     */
    fun convertToComponent(message: String): Component {
        return MiniMessage.miniMessage()
                .deserialize(replaceFormatCodes(message))
                .decoration(TextDecoration.ITALIC, false)
    }

    /**
     * Replaces legacy color codes with MiniMessage format.
     * Handles both standard color codes and hex colors.
     * 
     * @param message The message containing legacy color codes
     * @return The message with MiniMessage formatting
     */
    fun replaceFormatCodes(message: String): String {
        return message.replace("\u00BA", "&")
                .replace("\u00A7", "&")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&0", "<black>")
                .replace("&a", "<green>")
                .replace("&b", "<aqua>")
                .replace("&c", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&f", "<white>")
                .replace("&k", "<obf>")
                .replace("&l", "<b>")
                .replace("&m", "<st>")
                .replace("&n", "<u>")
                .replace("&o", "<i>")
                .replace("&r", "<reset>")
                .replace("&#([A-Fa-f0-9]{6})".toRegex(), "<color:#$1>")
    }

    /**
     * Splits text into lines of a maximum length while trying to maintain even line lengths.
     * Attempts to split at word boundaries when possible.
     * 
     * @param text The text to split
     * @param maxLineLength The maximum length for each line
     * @return List of lines containing the split text
     */
    fun splitIntoLines(text: String, maxLineLength: Int): List<String> {
        val words = text.split("\\s+".toRegex())
        val lines = mutableListOf<String>()

        // Calculate total length and minimum lines needed
        val totalSymbolLength = text.length
        val minLinesNeeded = (totalSymbolLength / maxLineLength) +
                if (totalSymbolLength % maxLineLength != 0) 1 else 0

        // Calculate average length per line
        val averageSymbolLengthPerLine = totalSymbolLength / minLinesNeeded

        // Distribute words among lines
        var currentLine = StringBuilder()
        var currentSymbolLength = 0
        var remainingLines = minLinesNeeded

        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word)
                currentSymbolLength += word.length
            } else if (currentSymbolLength + word.length + 1 <= averageSymbolLengthPerLine ||
                remainingLines == 1
            ) {
                currentLine.append(" ").append(word)
                currentSymbolLength += word.length + 1
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
                currentSymbolLength = word.length
                remainingLines--
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines
    }
}
