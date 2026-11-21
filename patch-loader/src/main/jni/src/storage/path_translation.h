#pragma once

#include <string>
#include <vector>
#include <set>
#include <mutex>
#include <cstdint>

namespace lspd::storage {

struct CustomRule {
    std::string virtual_prefix;
    std::string real_prefix;
    uint32_t flags = 0; // BIT 0: READ_ONLY, BIT 1: EMULATE_POSIX
};

class PathTranslator {
public:
    static PathTranslator& Instance();

    void Init(const std::string& container_root,
              const std::string& package_name,
              const std::string& internal_data_dir,
              bool redirect_external = true,
              bool redirect_internal = true);

    void AddNativeLibDir(const std::string& dir);
    void AddCustomRule(const CustomRule& rule);
    void ClearRules();

    // Normalizes POSIX paths: collapses /./, eliminates foo/../, removes duplicate //
    static std::string NormalizePath(const std::string& path);

    // Canonicalizes storage aliases (/sdcard, /storage/self/primary, /mnt/sdcard, /mnt/user/0/primary, /data/media/0 -> /storage/emulated/0)
    static std::string CanonicalizeAlias(const std::string& path);

    // Checks if path is already inside container or inside a custom rule's real destination
    bool IsAlreadyRealPath(const std::string& path) const;

    // Checks if path is an ancestor directory of any custom rule's real destination
    bool IsAncestorOfRealPrefix(const std::string& path) const;

    // Checks if path represents a native library or lives in a native library directory
    bool IsNativeLibLocation(const std::string& path) const;

    // Checks if path is exempt from storage redirection (core loader, webview, code_cache, etc.)
    bool IsExempt(const std::string& path) const;

    // Translates virtual path to real physical path.
    // Returns true if path was redirected, false if untouched.
    bool ToRealPath(const std::string& virtual_path, std::string& out_real_path) const;

    // Translates real physical path back to virtual path.
    // Returns true if path was reverse-mapped, false if untouched.
    bool ToVirtualPath(const std::string& real_path, std::string& out_virtual_path) const;

    const std::string& ContainerRoot() const { return container_root_; }
    const std::string& PackageName() const { return package_name_; }
    const std::string& InternalDataDir() const { return internal_data_dir_; }
    bool RedirectExternal() const { return redirect_external_; }
    bool RedirectInternal() const { return redirect_internal_; }

private:
    PathTranslator() = default;

    std::string container_root_;
    std::string package_name_;
    std::string internal_data_dir_;
    bool redirect_external_ = true;
    bool redirect_internal_ = true;

    mutable std::mutex mutex_;
    std::set<std::string> native_lib_dirs_;
    std::vector<CustomRule> custom_rules_; // Kept sorted longest virtual_prefix first
};

} // namespace lspd::storage
