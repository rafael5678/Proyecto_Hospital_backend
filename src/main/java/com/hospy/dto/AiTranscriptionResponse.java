package com.hospy.dto;

/** JSON body for speech-to-text replies. */
public record AiTranscriptionResponse(String texto, boolean modoDemo, String mensaje) { }