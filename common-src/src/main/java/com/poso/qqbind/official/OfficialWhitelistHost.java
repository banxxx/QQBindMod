package com.poso.qqbind.official;

import java.nio.file.Path;

/** Loader-owned access to the exact whitelist used by vanilla login checks. */
public interface OfficialWhitelistHost extends OfficialWhitelistQueue.GameAccess, OfficialWhitelistQueue.ProfileResolver, AutoCloseable {
    Path filePath();
    boolean activeFast();
    void attachOnGame(OfficialWhitelistFile file);
    void tickOnGame();
    @Override void validateProfileOnGame(OfficialWhitelistFile.Profile profile);
    @Override void close();
}
