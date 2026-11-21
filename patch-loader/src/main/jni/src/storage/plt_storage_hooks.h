#pragma once

#include <string>

namespace lspd::storage {

// Initializes and installs PLT hooks across target libraries (libopenjdk, libjavacore, libsqlite, libandroid_runtime, and app libs)
bool InstallStoragePltHooks();

// Rescans and hooks any newly loaded application libraries (called after dlopen)
void HookAppNativeLibraries();

} // namespace lspd::storage
