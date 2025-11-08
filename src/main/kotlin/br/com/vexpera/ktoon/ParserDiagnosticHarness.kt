package br.com.vexpera.ktoon

import br.com.vexpera.ktoon.decoder.Decoder
import br.com.vexpera.ktoon.decoder.DecoderOptions
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Ferramenta de diagnóstico do parser TOON.
 *
 * Lê todos os arquivos .toon em src/test/resources/spec e src/test/resources
 * e exibe logs detalhados de parsing, incluindo:
 *  - conteúdo linha a linha (com indentação visível)
 *  - estrutura decodificada em JSON
 *  - exceções lançadas
 *
 * Útil para detectar onde o parser relê o cabeçalho ou conta linhas erradas.
 */
object ParserDiagnostic {

    @JvmStatic
    fun main(args: Array<String>) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val decoder = Decoder(DecoderOptions(strict = true, debug = true))

        val specDir = File("src/test/resources/spec")
        val testDir = File("src/test/resources")

        val allToons = (specDir.walkTopDown() + testDir.walkTopDown())
            .filter { it.extension == "toon" }
            .sortedBy { it.name }

        println("=== TOON Parser Diagnostic ===")
        println("Encontrados ${allToons.count()} arquivos .toon\n")

        for (file in allToons) {
            println("==========================================")
            println("📄 Arquivo: ${file.name}")
            println("📂 Caminho: ${file.path}")
            println("------------------------------------------")

            val text = file.readText()
            val lines = text.split("\n")
            for ((i, line) in lines.withIndex()) {
                val visible = line.replace("\t", "\\t").replace(" ", "·")
                println("${(i + 1).toString().padStart(2)} | $visible")
            }

            println("\n--- Parsing com debug ---")
            try {
                val result = decoder.decode(text)
                val json = gson.toJson(result)
                println("✅ Parsed com sucesso!")
                println(json)
            } catch (e: ToonParseException) {
                println("❌ ToonParseException: ${e.message}")
            } catch (e: Exception) {
                println("💥 Exception inesperada: ${e::class.simpleName}: ${e.message}")
            }

            println("==========================================\n")
        }
    }
}
