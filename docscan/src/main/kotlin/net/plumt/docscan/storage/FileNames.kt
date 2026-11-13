package net.plumt.docscan.storage

/** 파일 이름·하위 폴더 검사. 정규식을 쓰지 않는다(Android·JVM 엔진 차이를 피한다). */
internal object FileNames {
    const val MAX_NAME_LENGTH = 120
    private const val FORBIDDEN = "/\\:*?\"<>|"
    private const val FIRST_PRINTABLE = 0x20

    /** 표시 이름을 검사하고 `.pdf` 확장자를 붙인다. */
    fun pdfName(displayName: String): String {
        val name = displayName.trim()
        require(name.isNotEmpty()) { "displayName 이 비었다" }
        require(name.length <= MAX_NAME_LENGTH) { "displayName 은 ${MAX_NAME_LENGTH}자 이하여야 한다: ${name.length}자" }
        require(name != "." && name != "..") { "displayName 으로 쓸 수 없다: $name" }
        require(name.none { it in FORBIDDEN || it.code < FIRST_PRINTABLE }) { "displayName 에 쓸 수 없는 문자($FORBIDDEN 또는 제어 문자)가 있다: $name" }
        return if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"
    }

    /** 하위 폴더(`a/b`)를 검사한다. null·빈 값은 null. */
    fun subdirectory(value: String?): String? {
        if (value == null) return null
        val trimmed = value.trim().trim('/')
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('/')
        for (p in parts) {
            require(p.isNotEmpty() && p != "." && p != "..") { "subdirectory 의 경로 조각으로 쓸 수 없다: '$p' ($value)" }
            require(p.none { it in FORBIDDEN || it.code < FIRST_PRINTABLE }) { "subdirectory 에 쓸 수 없는 문자가 있다: $value" }
        }
        require(trimmed.length <= MAX_NAME_LENGTH) { "subdirectory 는 ${MAX_NAME_LENGTH}자 이하여야 한다: $value" }
        return parts.joinToString("/")
    }

    /**
     * 같은 이름이 있으면 `이름 (1).pdf` 처럼 1 부터 번호를 붙인다.
     * API 29+ 의 MediaStore(MediaProvider)가 같은 이름에 붙이는 형식과 맞춘다.
     */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }
}
