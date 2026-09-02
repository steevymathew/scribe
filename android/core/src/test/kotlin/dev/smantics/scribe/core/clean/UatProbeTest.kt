package dev.smantics.scribe.core.clean

import org.junit.Test

/** THROWAWAY UAT probe - prints Clean renderings of realistic dictated sentences. */
class UatProbeTest {
    private val ctx = CleanContext()

    private fun show(s: String) {
        val out = CleanPipeline.run(s, ctx)
        val flag = if (out != s) "  <-- CHANGED" else ""
        println("IN : $s")
        println("OUT: $out$flag")
        println()
    }

    @Test
    fun probe() {
        val samples = listOf(
            // --- SpokenPunctuation ambiguity
            "the trial period ends tomorrow so we should decide",
            "my notice period is three months",
            "I'd like to quote Shakespeare on this",
            "she gave me a quote for the work",
            "go to example dot com forward slash pricing",
            "he won the hundred metre dash",
            "the car came to a full stop at the lights",
            "should there be a hyphen in the name",
            "we had hash browns for breakfast",
            "a period of time",
            "the dash to the door",
            // --- SelfCorrection
            "I'm sorry John can't make it tonight",
            "I'm sorry, David is out of the office",
            "sorry Monday doesn't work for me",
            "let's meet at 4pm actually 3pm",
            "we had to scratch that plan and start over",
            "please ignore that last message I sent",
            "can you delete that file when you get a chance",
            "I want to strike that clause from the contract",
            "I actually agree with you",
            "yeah actually Steve said the same thing",
            "I think Tom, sorry, Tim is the one you want",
            // --- Fillers / disfluency
            "err on the side of caution here",
            "the tolerance is about 5 mm either way",
            "no no no that is not what I meant",
            "bye bye see you tomorrow",
            "ha ha ha that was funny",
            "the room number is one one two",
            "that is a very very very important point",
            "I had had enough by then",
            "um so I think we should ship it",
            "I think, um, we should go now",
            // --- Numbers
            "I'm one hundred percent sure about this",
            "it costs twenty five dollars",
            "the meeting is at three hundred euros no wait",
            "let's meet at five o'clock",
            "see you at 4 p.m.",
            "the discount is ten per cent",
            "I am going home now",
            "we need 3 amps for that circuit",
            // --- Lists
            "first of all we should ship. second we should tell them.",
            "firstly I want to thank you all. secondly we need to talk about the budget.",
            "first we eat. second we sleep. third we repeat.",
            // --- Ordinary sentences that must survive untouched
            "can you send me the file when you get a chance",
            "I'll be there in about twenty minutes",
            "the meeting moved to Thursday at 2pm",
        )
        samples.forEach(::show)
    }
}
