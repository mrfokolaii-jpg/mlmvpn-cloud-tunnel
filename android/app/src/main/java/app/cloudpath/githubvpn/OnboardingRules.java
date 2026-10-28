package app.cloudpath.githubvpn;

/** Pure onboarding decisions, kept separate so sign-in never silently starts a VPN. */
final class OnboardingRules {
    enum Next { SETUP_REPOSITORY, REQUEST_VPN, READY }
    static String clientId(String bundled, String developerOverride) {
        String bundledId = bundled == null ? "" : bundled.trim();
        String override = developerOverride == null ? "" : developerOverride.trim();
        // A distributed APK's registered app identity always wins over an old debug override.
        return bundledId.isEmpty() ? override : bundledId;
    }
    static Next afterLogin(boolean hasRepository, boolean userRequestedStart) {
        if (!hasRepository) return Next.SETUP_REPOSITORY;
        return userRequestedStart ? Next.REQUEST_VPN : Next.READY;
    }
}
