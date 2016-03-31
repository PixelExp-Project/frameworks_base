/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import android.os.SystemProperties;

import dalvik.system.DexFile;

/**
 * Manage (retrieve) mappings from compilation reason to compilation filter.
 */
class PackageManagerServiceCompilerMapping {
    // Names for compilation reasons.
    static final String REASON_STRINGS[] = {
            "first-boot", "boot", "install", "bg-dexopt", "ab-ota", "nsys-library", "shared-apk",
            "forced-dexopt"
    };

    // Static block to ensure the strings array is of the right length.
    static {
        if (PackageManagerService.REASON_LAST + 1 != REASON_STRINGS.length) {
            throw new IllegalStateException("REASON_STRINGS not correct");
        }
    }

    private static String getSystemPropertyName(int reason) {
        if (reason < 0 || reason >= REASON_STRINGS.length) {
            throw new IllegalArgumentException("reason " + reason + " invalid");
        }

        return "pm.dexopt." + REASON_STRINGS[reason];
    }

    // Load the property for the given reason and check for validity. This will throw an
    // exception in case the reason or value are invalid.
    private static String getAndCheckValidity(int reason) {
        String sysPropValue = SystemProperties.get(getSystemPropertyName(reason));
        if (sysPropValue == null || sysPropValue.isEmpty() ||
                !DexFile.isValidCompilerFilter(sysPropValue)) {
            throw new IllegalStateException("Value \"" + sysPropValue +"\" not valid "
                    + "(reason " + REASON_STRINGS[reason] + ")");
        }

        // Ensure that some reasons are not mapped to profile-guided filters.
        switch (reason) {
            case PackageManagerService.REASON_SHARED_APK:
            case PackageManagerService.REASON_FORCED_DEXOPT:
                if (DexFile.isProfileGuidedCompilerFilter(sysPropValue)) {
                    throw new IllegalStateException("\"" + sysPropValue + "\" is profile-guided, "
                            + "but not allowed for " + REASON_STRINGS[reason]);
                }
                break;
        }

        return sysPropValue;
    }

    // Check that the properties are set and valid.
    // Note: this is done in a separate method so this class can be statically initialized.
    static void checkProperties() {
        // We're gonna check all properties and collect the exceptions, so we can give a general
        // overview. Store the exceptions here.
        RuntimeException toThrow = null;

        for (int reason = 0; reason <= PackageManagerService.REASON_LAST; reason++) {
            try {
                // Check that the system property name is legal.
                String sysPropName = getSystemPropertyName(reason);
                if (sysPropName == null ||
                        sysPropName.isEmpty() ||
                        sysPropName.length() > SystemProperties.PROP_NAME_MAX) {
                    throw new IllegalStateException("Reason system property name \"" +
                            sysPropName +"\" for reason " + REASON_STRINGS[reason]);
                }

                // Check validity, ignore result.
                getAndCheckValidity(reason);
            } catch (Exception exc) {
                if (toThrow == null) {
                    toThrow = new IllegalStateException("PMS compiler filter settings are bad.");
                }
                toThrow.addSuppressed(exc);
            }
        }

        if (toThrow != null) {
            throw toThrow;
        }
    }

    public static String getCompilerFilterForReason(int reason) {
        return getAndCheckValidity(reason);
    }

    /**
     * Return the compiler filter for "full" compilation.
     *
     * We derive that from the traditional "dalvik.vm.dex2oat-filter" property and just make
     * sure this isn't profile-guided. Returns "speed" in case of invalid (or missing) values.
     */
    public static String getFullCompilerFilter() {
        String value = SystemProperties.get("dalvik.vm.dex2oat-filter");
        if (value == null || value.isEmpty()) {
            return "speed";
        }

        if (!DexFile.isValidCompilerFilter(value) ||
                DexFile.isProfileGuidedCompilerFilter(value)) {
            return "speed";
        }

        return value;
    }

    /**
     * Return the non-profile-guided filter corresponding to the given filter.
     */
    public static String getNonProfileGuidedCompilerFilter(String filter) {
        return DexFile.getNonProfileGuidedCompilerFilter(filter);
    }
}
