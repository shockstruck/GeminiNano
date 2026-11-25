package dev.shockstruck.gemininano.installedcodefixture;

public final class InstalledCodeFixture {

    static final int CONSTANT = 0x2a;
    static final String LABEL = "InstalledCodeFixture";
    static int counter = 1;

    private InstalledCodeFixture() {
    }

    static final class FlagManager {
        static boolean getBoolean(String name, boolean fallback) {
            return fallback;
        }

        static int getInt(String name, int fallback) {
            return fallback;
        }
    }

    static void probe() {
        FlagManager.getBoolean("ProtectedDownload__enabled", false);
        FlagManager.getInt("AicDataRelease__build_id_aicore", 0);
        FlagManager.getBoolean("PcsAi__enable_genai_inference_service", true);
    }

    static int choose(int selector, int value) {
        int result;
        switch (selector) {
            case 0:
                result = value + CONSTANT;
                break;
            case 1:
                result = value - CONSTANT;
                break;
            case 7:
                result = value * 2;
                break;
            default:
                result = value;
        }
        if (result < 0) {
            result = -result;
        }
        return result + counter;
    }

    static String label() {
        return LABEL;
    }
}
