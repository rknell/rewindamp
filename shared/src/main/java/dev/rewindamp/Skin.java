package dev.rewindamp;

final class Skin {
    static final int BG = 0xFF171723, PANEL = 0xFF29293D, GREEN = 0xFF25F11B,
        MUTED = 0xFF9DA5B2, WHITE = 0xFFE1E4E7, LCD = 0xFF030604, GOLD = 0xFFCEBE7A;
    static final int RAIL_EDGE = 0xFF8F8051, RAIL_LIGHT = 0xFFF6EFC2, RAIL_MID = 0xFFD4C583;
    static final int METAL_LIGHT = 0xFFF1F5F5, METAL_MID = 0xFFA9BBC5, METAL_BASE = 0xFFCCD7DC;
    private static final int[] DIGITS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

    static int digit(char value) {
        if (value >= '0' && value <= '9') return DIGITS[value - '0'];
        return value == '-' || value == '−' ? 0x40 : 0;
    }
}
