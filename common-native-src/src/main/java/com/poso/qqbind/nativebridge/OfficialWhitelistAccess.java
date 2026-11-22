package com.poso.qqbind.nativebridge;

import com.poso.qqbind.official.OfficialWhitelistFile;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Narrow memory bridge implemented on the original vanilla whitelist instance. */
public interface OfficialWhitelistAccess {
    void qqbind$attach(OfficialWhitelistFile file, BooleanSupplier active, BooleanSupplier gameThread);
    void qqbind$detach(OfficialWhitelistFile file);
    List<OfficialWhitelistFile.Profile> qqbind$snapshot();
    void qqbind$apply(OfficialWhitelistFile.Profile profile, boolean active);
}
