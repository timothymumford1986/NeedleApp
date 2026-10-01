package app.needler.licences

/**
 * The attribution line and the disclaimer, in one place, because two screens have to show the same
 * words and REQUIREMENTS.md treats those words as a requirement.
 *
 * REQUIREMENTS.md "Legal and attribution": "Screen 12 carries the disclaimer text, and **it is a
 * requirement rather than decoration**. It states that Needler is independent and unaffiliated, that
 * it hosts and transmits no music, that the user is responsible for what they acquire, and that the
 * app is provided without warranty. The attribution line credits Dropped Needle, slskd, MusicBrainz,
 * ListenBrainz and Cover Art Archive."
 *
 * ## Why this file exists rather than the copy living in the screen that draws it
 *
 * It lived in `SettingsScreen.kt` as two private constants, which was right while Settings was the
 * only screen that showed them. The Licences screen shows the same disclaimer — it is what the
 * "Licences and full terms" button promises — and a legal statement that exists twice in a codebase
 * is a legal statement that will eventually say two different things. One of the copies gets a
 * typo fixed, or a clause softened by someone tidying prose, and nothing anywhere fails.
 *
 * So there is one copy, and `SettingsScreen` and `LicencesScreen` both read it. It lives in the
 * licences package rather than in settings because this is the text's subject: the Licences screen is
 * where the full terms are, and Settings shows them because a user looking for them starts there.
 *
 * **Do not reword any of this without advice.** The four paragraphs of [disclaimer] are the design
 * pack's own copy, verbatim, and rewording one of them would be a legal change made by a UI file.
 */
internal object NeedlerLegal {

    /**
     * The five services REQUIREMENTS.md names, in the order the design pack credits them.
     *
     * None of them is a bundled dependency and none of them appears in [LicenceCatalogue] for that
     * reason: Needler links no code of theirs. They are credited because the product would not exist
     * without them and because REQUIREMENTS.md requires the line, not because a licence compels it.
     */
    val credits: List<String> = listOf(
        "Dropped Needle",
        "slskd",
        "MusicBrainz",
        "ListenBrainz",
        "Cover Art Archive",
    )

    /** How [credits] is introduced, and the sentence that closes it. */
    const val CREDITS_PREFIX: String = "With thanks to "
    const val CREDITS_SUFFIX: String = ". All the heavy lifting is theirs."

    /**
     * What each credited service actually is, for the Licences screen only.
     *
     * Settings prints the bare line the pack draws. This screen has the room to say why each name is
     * there, and "why is MusicBrainz in my music player" is a reasonable question from someone
     * reading a legal screen. Keyed by the same strings as [credits] so the two cannot fall out of
     * step; a name with no entry simply gets no second line.
     */
    val creditDescriptions: Map<String, String> = mapOf(
        "Dropped Needle" to "The server Needler is a client for. You run it; it does all the work.",
        "slskd" to "One of the sources your server can acquire music through.",
        "MusicBrainz" to "The open music encyclopedia every album, artist and track identity here " +
            "comes from.",
        "ListenBrainz" to "Where your plays are reported, if your server is configured to report them.",
        "Cover Art Archive" to "Where album artwork comes from when your own files carry none.",
    )

    /**
     * The design pack's legal copy, verbatim, in the order it is drawn.
     *
     * Four paragraphs, and REQUIREMENTS.md names the job of each: Needler is independent and
     * unaffiliated; it hosts and transmits no music; the user is responsible for what they acquire;
     * the app carries no warranty.
     */
    val disclaimer: List<String> = listOf(
        "Needler is an independent client for a Dropped Needle server that you install, configure " +
            "and operate yourself. It is not affiliated with, endorsed by or sponsored by Dropped " +
            "Needle, slskd, Soulseek, MusicBrainz, ListenBrainz or any other service it talks to.",
        "Needler does not host, store, index, search for or transmit any music. Every search, " +
            "download and stream is performed by your own server and the services you have " +
            "connected to it, under your control and your accounts.",
        "You are solely responsible for what you search for, download and play, for holding the " +
            "rights to do so, and for complying with copyright law and the terms of every service " +
            "you use. Needler makes no representation that any content is licensed or lawful to " +
            "obtain in your country.",
        "Needler is provided \"as is\" and \"as available\", without warranty of any kind, express " +
            "or implied, including fitness for a particular purpose, non-infringement and " +
            "uninterrupted operation. To the fullest extent permitted by law, the developer accepts " +
            "no liability for any loss, damage or claim arising from your use of Needler or of any " +
            "server or service it connects to.",
    )
}
