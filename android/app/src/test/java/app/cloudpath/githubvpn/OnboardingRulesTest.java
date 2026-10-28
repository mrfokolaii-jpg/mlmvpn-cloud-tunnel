package app.cloudpath.githubvpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class OnboardingRulesTest {
    @Test public void bundledApplicationIdentityTakesPrecedenceOverAnOldDebugOverride() {
        assertEquals("public-id-for-all-users", OnboardingRules.clientId("public-id-for-all-users", "old-debug-id"));
        assertEquals("developer-id", OnboardingRules.clientId("", " developer-id "));
        assertEquals("", OnboardingRules.clientId(null, null));
    }
    @Test public void loginOffersSetupButNeverStartsVpnWithoutTheUserRequestingIt() {
        assertEquals(OnboardingRules.Next.SETUP_REPOSITORY, OnboardingRules.afterLogin(false, false));
        assertEquals(OnboardingRules.Next.SETUP_REPOSITORY, OnboardingRules.afterLogin(false, true));
        assertEquals(OnboardingRules.Next.READY, OnboardingRules.afterLogin(true, false));
        assertEquals(OnboardingRules.Next.REQUEST_VPN, OnboardingRules.afterLogin(true, true));
    }
}
