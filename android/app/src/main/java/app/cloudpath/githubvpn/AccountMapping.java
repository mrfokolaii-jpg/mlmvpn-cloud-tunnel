package app.cloudpath.githubvpn;

import java.util.Locale;

/** Account names are case-insensitive; an old account's repository is never reused for a new one. */
final class AccountMapping {
    static String key(String prefix, String login) {
        if (login == null || !login.matches("[A-Za-z0-9-]{1,39}")) throw new IllegalArgumentException("invalid GitHub login");
        return prefix + login.toLowerCase(Locale.ROOT);
    }
    static String restore(String previousLogin, String newLogin, String savedRepo, String activeRepo) {
        if (SessionRules.validRepo(savedRepo)) return savedRepo;
        if (previousLogin != null && previousLogin.equalsIgnoreCase(newLogin) && SessionRules.validRepo(activeRepo)) return activeRepo;
        return "";
    }
}
