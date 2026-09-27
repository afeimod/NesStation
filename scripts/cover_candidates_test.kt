// 独立验证 CoverFetcher.nameCandidates 增强逻辑（无 Android 依赖）
import kotlin.collections.LinkedHashSet

private const val MAX_CANDIDATES = 16

private val REGION_ALIASES: Map<String, List<String>> = mapOf(
    "U" to listOf("USA"), "US" to listOf("USA"), "USA" to listOf("USA"),
    "J" to listOf("Japan"), "JP" to listOf("Japan"), "JPN" to listOf("Japan"), "JAPAN" to listOf("Japan"),
    "E" to listOf("Europe"), "EU" to listOf("Europe"), "EUR" to listOf("Europe"), "EUROPE" to listOf("Europe"),
    "W" to listOf("World"), "WORLD" to listOf("World"),
    "UK" to listOf("UK"),
    "AU" to listOf("Australia"), "AUS" to listOf("Australia"),
    "AS" to listOf("Asia"), "ASIA" to listOf("Asia"),
    "CN" to listOf("China"), "CHN" to listOf("China"), "CHINA" to listOf("China"),
    "TW" to listOf("Taiwan"), "TWN" to listOf("Taiwan"), "TAIWAN" to listOf("Taiwan"),
    "KR" to listOf("Korea"), "KOR" to listOf("Korea"), "KOREA" to listOf("Korea"),
    "BR" to listOf("Brazil"), "BRA" to listOf("Brazil"), "BRAZIL" to listOf("Brazil"),
    "CA" to listOf("Canada"), "CAN" to listOf("Canada"), "CANADA" to listOf("Canada"),
    "F" to listOf("France"), "FR" to listOf("France"), "FRANCE" to listOf("France"),
    "G" to listOf("Germany"), "DE" to listOf("Germany"), "GER" to listOf("Germany"), "GERMANY" to listOf("Germany"),
    "ES" to listOf("Spain"), "SPA" to listOf("Spain"), "SPAIN" to listOf("Spain"),
    "I" to listOf("Italy"), "IT" to listOf("Italy"), "ITALY" to listOf("Italy"),
    "NL" to listOf("Netherlands"), "NED" to listOf("Netherlands"), "NETHERLANDS" to listOf("Netherlands"),
    "SW" to listOf("Sweden"), "SWE" to listOf("Sweden"), "SWEDEN" to listOf("Sweden"),
    "RU" to listOf("Russia"), "RUS" to listOf("Russia"), "RUSSIA" to listOf("Russia"),
    "HK" to listOf("Hong Kong"),
    "GR" to listOf("Greece"), "GREECE" to listOf("Greece"),
    "NO" to listOf("Norway"), "NOR" to listOf("Norway"), "NORWAY" to listOf("Norway"),
    "SC" to listOf("Scandinavia"), "SCANDINAVIA" to listOf("Scandinavia"),
    "JU" to listOf("World", "USA", "Japan", "Europe"),
    "UJ" to listOf("World", "USA", "Japan", "Europe"),
    "JUE" to listOf("World", "USA", "Europe", "Japan"),
    "UJE" to listOf("World", "USA", "Europe", "Japan"),
    "JEU" to listOf("World", "USA", "Europe", "Japan"),
    "EJU" to listOf("World", "USA", "Europe", "Japan"),
    "EUJ" to listOf("World", "USA", "Europe", "Japan"),
    "UE" to listOf("World", "USA", "Europe"),
    "JE" to listOf("World", "Japan", "Europe"),
    "EJ" to listOf("World", "Japan", "Europe"),
    "WE" to listOf("World", "Europe"),
    "USJ" to listOf("World", "USA", "Japan"),
    "USE" to listOf("World", "USA", "Europe")
)

private fun dotTitle(name: String): String? {
    if (name.isBlank()) return null
    val idx = name.indexOf('(')
    val title = if (idx >= 0) name.substring(0, idx).trim() else name.trim()
    if (title.isEmpty()) return null
    val last = title.last()
    if (!last.isLetterOrDigit() && last !in "!?") return null
    return if (idx >= 0) "$title. ${name.substring(idx)}" else "$title."
}

private fun mapRegions(name: String): List<String> {
    if (name.isEmpty()) return emptyList()
    val results = LinkedHashSet<String>()
    val tagPattern = Regex("\\(([^)]+)\\)")
    for (m in tagPattern.findAll(name)) {
        val code = m.groupValues[1].trim().uppercase()
        val mapped = REGION_ALIASES[code] ?: continue
        for (r in mapped) {
            results.add(name.replaceRange(m.range, "($r)"))
        }
    }
    return results.toList()
}

private fun nameCandidates(rawName: String): List<String> {
    val n0 = rawName.trim().replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    if (n0.isEmpty()) return emptyList()
    val candidates = LinkedHashSet<String>()
    val noBrackets = n0.replace(Regex("\\s*\\[[^]]*]"), "").trim()
    val noTags = noBrackets.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
    candidates.add(n0)
    if (noBrackets.isNotBlank() && noBrackets != n0) candidates.add(noBrackets)
    if (noTags.isNotBlank() && noTags != n0 && noTags != noBrackets) candidates.add(noTags)
    for (v in listOf(n0, noBrackets, noTags)) {
        dotTitle(v)?.let { candidates.add(it) }
    }
    for (mapped in mapRegions(noBrackets)) {
        candidates.add(mapped)
        dotTitle(mapped)?.let { candidates.add(it) }
    }
    return candidates.toList().take(MAX_CANDIDATES)
}

fun main() {
    val samples = listOf(
        "Super Mario Bros (JU) [!]",
        "Contra (U) [!]",
        "Metroid Fusion (U)",
        "Sonic the Hedgehog (U) [!]",
        "Kirby's Adventure (USA)",
        "Mega Man 2 (JU)",
        "Final Fantasy VI (UE) [!]",
        "Super Bomberman 3 (J)",
        "Pocky & Rocky 2 (U) [!]",
        "Tetris",
        "Metal Slug (Asia)",
        "魂斗罗",
        "007 - Licence to Kill (Europe)"
    )
    for (s in samples) {
        val cands = nameCandidates(s)
        println("[$s] -> ${cands.size}")
        cands.forEach { println("    $it") }
        println()
    }
}
