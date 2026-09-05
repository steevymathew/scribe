package dev.smantics.scribe.ime

/**
 * The emoji the keyboard offers, and nothing else about them.
 *
 * A deliberately small, fixed, hand-picked set rather than the full Unicode range. Three
 * reasons, in order of how much they matter:
 *
 *  1. **No recents, no frequency, no search.** All three mean keeping a record of what the
 *     user typed, and not keeping one is the promise this whole app is built on. A picker
 *     that learns is a picker that remembers.
 *  2. A phone renders whatever its own font has. Shipping a list built from a Unicode
 *     table means offering glyphs that arrive as empty boxes on the device in the user's
 *     hand; these are all long-established and render everywhere Scribe runs.
 *  3. Scrolling past two thousand emoji to find the one you wanted is not a feature.
 *
 * If this ever needs to grow, it grows by someone adding characters here on purpose.
 */
object Emoji {

    /** Category name to glyphs, in the order the page shows them. */
    val categories: List<Pair<String, List<String>>> = listOf(
        "SMILEYS" to listOf(
            "😀", "😃", "😄", "😁", "😅", "😂", "🙂", "🙃",
            "😉", "😊", "😍", "😘", "😗", "😜", "🤪", "🤨",
            "🧐", "🤓", "😎", "🥳", "😏", "🤔", "😌", "😔",
            "😢", "😭", "😤", "😠", "🤯", "😳", "🥺", "😱",
            "😴", "🤤", "😷", "🤒", "🤕", "🤢", "🥴", "😇",
        ),
        "PEOPLE" to listOf(
            "👋", "🤚", "✋", "👌", "🤌", "✌️", "🤞", "🤙",
            "👍", "👎", "👊", "🙌", "👏", "🙏", "💪", "🤝",
            "👀", "🧠", "👤", "👶", "🧑", "👩", "👨", "🧓",
        ),
        "HEARTS" to listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
            "💔", "❣️", "💕", "💖", "💗", "💘", "💝", "💯",
        ),
        "NATURE" to listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
            "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
            "🦄", "🐝", "🦋", "🌸", "🌻", "🌲", "🌍", "🌙",
            "⭐", "🔥", "🌈", "☀️", "☁️", "❄️", "💧", "🌊",
        ),
        "FOOD" to listOf(
            "🍎", "🍌", "🍇", "🍓", "🍑", "🍍", "🥑", "🍅",
            "🥕", "🌽", "🍞", "🧀", "🍕", "🍔", "🌮", "🍣",
            "🍜", "🍪", "🎂", "🍫", "☕", "🍵", "🍺", "🥂",
        ),
        "THINGS" to listOf(
            "📱", "💻", "⌨️", "🖱️", "🖨️", "🎧", "📷", "🔋",
            "💡", "🔑", "🔒", "📎", "✂️", "📌", "📁", "📄",
            "✏️", "🖊️", "📚", "💰", "🎁", "🎉", "🔔", "⏰",
        ),
        "TRAVEL" to listOf(
            "🚗", "🚕", "🚌", "🚲", "🛴", "✈️", "🚀", "🚂",
            "⛵", "🏠", "🏢", "🏥", "🏫", "🗺️", "🧭", "🏔️",
        ),
        "SYMBOLS" to listOf(
            "✅", "❌", "⚠️", "❓", "❗", "➕", "➖", "➗",
            "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚫", "⚪",
            "⬆️", "⬇️", "⬅️", "➡️", "🔄", "♻️", "🆗", "🆕",
        ),
    )
}
