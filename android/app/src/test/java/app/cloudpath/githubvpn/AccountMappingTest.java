package app.cloudpath.githubvpn;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AccountMappingTest {
    @Test public void repositoryDoesNotLeakAcrossGithubIdentities() {
        assertEquals("", AccountMapping.restore("alice", "bob", "", "alice/private-tunnel"));
        assertEquals("bob/private-tunnel", AccountMapping.restore("alice", "bob", "bob/private-tunnel", "alice/private-tunnel"));
        assertEquals("alice/private-tunnel", AccountMapping.restore("alice", "ALICE", "", "alice/private-tunnel"));
    }

    @Test public void credentialKeysNormalizeGithubLoginCase() {
        assertEquals(AccountMapping.key("credentials.", "Alice"), AccountMapping.key("credentials.", "alice"));
    }
}
