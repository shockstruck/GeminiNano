package dev.shockstruck.gemininano.installedcodefixture;

public final class InstalledCodeFixture {

    static final int CONSTANT = 0x2a;
    static final String LABEL = "InstalledCodeFixture";
    static int counter = 1;
    static int extra;

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

    /** A gate whose default is a boxed Integer, as in the loader's flag constructors. */
    static final class IntFlag {
        private final String name;
        private final Integer fallback;

        IntFlag(String name, Integer fallback) {
            this.name = name;
            this.fallback = fallback;
        }

        Integer value() {
            return fallback;
        }
    }

    /** A gate whose default is a boxed Boolean. */
    static final class BoolFlag {
        private final String name;
        private final Boolean fallback;

        BoolFlag(String name, Boolean fallback) {
            this.name = name;
            this.fallback = fallback;
        }

        Boolean value() {
            return fallback;
        }
    }

    // A static final flag object: the boxed false is a literal in the static initializer.
    static final BoolFlag ENABLED_FLAG =
        new BoolFlag("PccSecurity__enable_security_check", Boolean.FALSE);

    static void probe() {
        FlagManager.getBoolean("ProtectedDownload__enabled", false);
        FlagManager.getInt("AicDataRelease__build_id_aicore", 0);
        FlagManager.getBoolean("PcsAi__enable_genai_inference_service", true);
    }

    /**
     * The build-id key is composed at run time from the bare prefix, and the default is a
     * boxed zero, as in the flag loader this app inspects.
     */
    static IntFlag runtimeBuildIdFlag(int client) {
        return new IntFlag(
            "AicDataRelease__build_id_".concat(String.valueOf(client)),
            Integer.valueOf(0));
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

    static int origin() {
        return CONSTANT;
    }
}
