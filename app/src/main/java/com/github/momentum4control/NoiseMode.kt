package com.github.momentum4control

enum class NoiseMode {
    OFF, ANC, AMB;

    fun shortLabel(): String = when (this) {
        OFF -> "Off"
        ANC -> "ANC"
        AMB -> "AMB"
    }
}
