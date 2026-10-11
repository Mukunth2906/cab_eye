package com.cabeye.backend.mindmap;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What one sentence a blind rider said means for their personal mind map. The JSON schema the
 * model must answer in is derived from this record, so every field is always present; an empty
 * string means "not said".
 */
public record Interpretation(
        @JsonPropertyDescription("REMEMBER: a personal note about a place or landmark. RIDE_CUE: something felt, heard or smelt on a cab ride. NONE: neither.")
        String intent,
        @JsonPropertyDescription("What the note or cue is about, in the rider's words, without colours: \"ravi's tea shop\", \"temple bell\", \"speed bump\". Empty for NONE.")
        String subject,
        @JsonPropertyDescription("A person the rider connects with it, as they named them (\"Ravi\", \"your uncle\"), else empty.")
        String person,
        @JsonPropertyDescription("The rider's habit there, in the second person: \"you stop on fridays\". Else empty.")
        String habit,
        @JsonPropertyDescription("What it tells the rider, in the second person: \"you're near amma's house\". Else empty.")
        String meaning,
        @JsonPropertyDescription("Another place the meaning points at (\"amma's house\"), else empty.")
        String relatesTo,
        @JsonPropertyDescription("How the rider senses it: SOUND, SMELL, TEXTURE, KERB, STEPS, RAMP, DOOR, CROSSING, OTHER; or NONE when it is only known, not sensed.")
        String sense,
        @JsonPropertyDescription("LEFT, RIGHT, AHEAD or NONE.")
        String side,
        @JsonPropertyDescription("0 to 1: how sure you are of this reading.")
        double confidence
) {
    public static final Interpretation NONE = new Interpretation("NONE", "", "", "", "", "", "NONE", "NONE", 0);
}
