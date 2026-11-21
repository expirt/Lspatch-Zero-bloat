#include "path_translation.h"

#include <algorithm>
#include <sstream>
#include <cstring>

namespace lspd::storage {

namespace {

static bool PathMatchesPrefix(const std::string& path, const std::string& prefix) {
    if (prefix.empty()) return false;
    if (path == prefix) return true;
    if (path.rfind(prefix, 0) == 0) {
        if (prefix.back() == '/') return true;
        if (path.length() > prefix.length() && path[prefix.length()] == '/') return true;
    }
    return false;
}

static void ReplaceAll(std::string& str, const std::string& from, const std::string& to) {
    if (from.empty()) return;
    size_t start_pos = 0;
    while ((start_pos = str.find(from, start_pos)) != std::string::npos) {
        str.replace(start_pos, from.length(), to);
        start_pos += to.length();
    }
}

static bool EndsWith(const std::string& str, const std::string& suffix) {
    if (str.length() < suffix.length()) return false;
    return str.compare(str.length() - suffix.length(), suffix.length(), suffix) == 0;
}

static bool Contains(const std::string& str, const std::string& substr) {
    return str.find(substr) != std::string::npos;
}

static std::string ToLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}

} // namespace

PathTranslator& PathTranslator::Instance() {
    static PathTranslator instance;
    return instance;
}

void PathTranslator::Init(const std::string& container_root,
                          const std::string& package_name,
                          const std::string& internal_data_dir,
                          bool redirect_external,
                          bool redirect_internal) {
    std::lock_guard<std::mutex> lock(mutex_);
    container_root_ = NormalizePath(container_root);
    package_name_ = package_name;
    internal_data_dir_ = NormalizePath(internal_data_dir);
    redirect_external_ = redirect_external;
    redirect_internal_ = redirect_internal;
}

void PathTranslator::AddNativeLibDir(const std::string& dir) {
    if (dir.empty()) return;
    std::string norm = NormalizePath(dir);
    std::lock_guard<std::mutex> lock(mutex_);
    native_lib_dirs_.insert(norm);
}

void PathTranslator::AddCustomRule(const CustomRule& rule) {
    CustomRule r = rule;
    ReplaceAll(r.virtual_prefix, "{pkg}", package_name_);
    ReplaceAll(r.real_prefix, "{pkg}", package_name_);
    ReplaceAll(r.virtual_prefix, "{container}", container_root_);
    ReplaceAll(r.real_prefix, "{container}", container_root_);

    r.virtual_prefix = NormalizePath(r.virtual_prefix);
    r.real_prefix = NormalizePath(r.real_prefix);

    std::lock_guard<std::mutex> lock(mutex_);
    custom_rules_.push_back(r);

    // If virtual_prefix starts with container_root_ (e.g. {container}/Download -> /storage/emulated/0/.fxr/Download):
    // Also synthesize the unmapped public virtual paths (/storage/emulated/0/Download, /sdcard/Download, etc.)
    if (!container_root_.empty() && PathMatchesPrefix(r.virtual_prefix, container_root_)) {
        std::string sub = r.virtual_prefix.substr(container_root_.length());
        
        // Check if it's internal data mirror: <container>/data/data/<pkg><sub...>
        std::string c_data = container_root_ + "/data/data/" + package_name_;
        if (PathMatchesPrefix(r.virtual_prefix, c_data)) {
            std::string data_sub = r.virtual_prefix.substr(c_data.length());
            std::string internal_base = !internal_data_dir_.empty() ? internal_data_dir_ : ("/data/user/0/" + package_name_);
            CustomRule r_int;
            r_int.virtual_prefix = NormalizePath(internal_base + data_sub);
            r_int.real_prefix = r.real_prefix;
            r_int.flags = r.flags;
            custom_rules_.push_back(r_int);

            CustomRule r_int2;
            r_int2.virtual_prefix = NormalizePath("/data/data/" + package_name_ + data_sub);
            r_int2.real_prefix = r.real_prefix;
            r_int2.flags = r.flags;
            custom_rules_.push_back(r_int2);
        } else {
            // Shared storage unmap: /storage/emulated/0<sub...>
            std::string unmapped_shared = NormalizePath("/storage/emulated/0" + sub);
            if (unmapped_shared != r.virtual_prefix) {
                CustomRule r_shared;
                r_shared.virtual_prefix = unmapped_shared;
                r_shared.real_prefix = r.real_prefix;
                r_shared.flags = r.flags;
                custom_rules_.push_back(r_shared);

                const char* kAliases[] = { "/sdcard", "/storage/self/primary", "/mnt/sdcard" };
                for (const char* alias : kAliases) {
                    CustomRule r_alias;
                    r_alias.virtual_prefix = NormalizePath(std::string(alias) + sub);
                    r_alias.real_prefix = r.real_prefix;
                    r_alias.flags = r.flags;
                    custom_rules_.push_back(r_alias);
                }
            }
        }
    } else if (PathMatchesPrefix(r.virtual_prefix, "/storage/emulated/0") && !container_root_.empty()) {
        // If virtual_prefix starts with /storage/emulated/0/Download:
        // Also synthesize the container_root_ variant ({container}/Download), and /sdcard variants
        std::string sub = r.virtual_prefix.substr(strlen("/storage/emulated/0"));
        std::string c_variant = NormalizePath(container_root_ + sub);
        if (c_variant != r.virtual_prefix) {
            CustomRule r_c;
            r_c.virtual_prefix = c_variant;
            r_c.real_prefix = r.real_prefix;
            r_c.flags = r.flags;
            custom_rules_.push_back(r_c);
        }
        const char* kAliases[] = { "/sdcard", "/storage/self/primary", "/mnt/sdcard" };
        for (const char* alias : kAliases) {
            CustomRule r_alias;
            r_alias.virtual_prefix = NormalizePath(std::string(alias) + sub);
            r_alias.real_prefix = r.real_prefix;
            r_alias.flags = r.flags;
            custom_rules_.push_back(r_alias);
        }
    }

    // Sort descending by virtual prefix length for longest-prefix match
    std::sort(custom_rules_.begin(), custom_rules_.end(),
              [](const CustomRule& a, const CustomRule& b) {
                  return a.virtual_prefix.length() > b.virtual_prefix.length();
              });
}

void PathTranslator::ClearRules() {
    std::lock_guard<std::mutex> lock(mutex_);
    custom_rules_.clear();
}

std::string PathTranslator::NormalizePath(const std::string& path) {
    if (path.empty()) return path;

    bool is_absolute = (path[0] == '/');
    std::vector<std::string> parts;
    std::stringstream ss(path);
    std::string segment;

    while (std::getline(ss, segment, '/')) {
        if (segment.empty() || segment == ".") {
            continue;
        }
        if (segment == "..") {
            if (!parts.empty() && parts.back() != "..") {
                parts.pop_back();
            } else if (!is_absolute) {
                parts.push_back("..");
            }
            // For absolute path, /.. collapses to /
        } else {
            parts.push_back(segment);
        }
    }

    if (parts.empty()) {
        return is_absolute ? "/" : ".";
    }

    std::string result;
    if (is_absolute) {
        result += "/";
    }
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i > 0) result += "/";
        result += parts[i];
    }
    return result;
}

std::string PathTranslator::CanonicalizeAlias(const std::string& path) {
    if (path.empty()) return path;

    const char* kAliases[] = {
        "/sdcard",
        "/storage/self/primary",
        "/mnt/sdcard",
        "/mnt/user/0/primary",
        "/data/media/0"
    };

    for (const char* alias : kAliases) {
        size_t len = strlen(alias);
        if (PathMatchesPrefix(path, alias)) {
            std::string sub = path.substr(len);
            return "/storage/emulated/0" + sub;
        }
    }

    return path;
}

bool PathTranslator::IsAlreadyRealPath(const std::string& path) const {
    if (!container_root_.empty() && PathMatchesPrefix(path, container_root_)) {
        return true;
    }
    for (const auto& rule : custom_rules_) {
        if (PathMatchesPrefix(path, rule.real_prefix)) {
            return true;
        }
    }
    if (IsAncestorOfRealPrefix(path)) {
        return true;
    }
    return false;
}

bool PathTranslator::IsAncestorOfRealPrefix(const std::string& path) const {
    if (path.empty() || path == "/" || path == "/storage" ||
        path == "/storage/emulated" || path == "/storage/emulated/0" ||
        path == "/sdcard" || path == "/storage/self/primary" || path == "/mnt/sdcard") {
        return false;
    }
    for (const auto& rule : custom_rules_) {
        if (rule.real_prefix.length() > path.length() &&
            rule.real_prefix.rfind(path, 0) == 0 &&
            rule.real_prefix[path.length()] == '/') {
            return true;
        }
    }
    return false;
}

bool PathTranslator::IsNativeLibLocation(const std::string& path) const {
    if (path.empty()) return false;

    // 1. Explicit native library extension patterns
    if (EndsWith(path, ".so") || Contains(path, ".so.") ||
        EndsWith(path, ".so.tmp") || Contains(path, ".so-")) {
        return true;
    }

    // 2. Known native library directories
    for (const auto& dir : native_lib_dirs_) {
        if (PathMatchesPrefix(path, dir)) {
            return true;
        }
    }

    // 3. Standard native library directory patterns under private data
    if (Contains(path, "/lib/") || EndsWith(path, "/lib") ||
        Contains(path, "/libs/") || EndsWith(path, "/libs") ||
        Contains(path, "/app_lib")) {
        return true;
    }

    return false;
}

bool PathTranslator::IsExempt(const std::string& path) const {
    if (path.empty()) return true;
    if (IsNativeLibLocation(path)) return true;

    // Anchored exemptions to internal data dir
    if (!internal_data_dir_.empty()) {
        if (PathMatchesPrefix(path, internal_data_dir_ + "/app_webview") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/app_lspatch") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/lspatch") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/code_cache") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/app_lib") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/lib") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/files/sdk") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/files/plugin") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/files/plugins") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/app_dex") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/app_p_dex") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/app_plugin") ||
            PathMatchesPrefix(path, internal_data_dir_ + "/app_plugins")) {
            return true;
        }
    } else {
        if (Contains(path, "/app_webview") || Contains(path, "/app_lspatch") ||
            Contains(path, "/lspatch/") || Contains(path, "/code_cache") ||
            Contains(path, "/files/sdk") || Contains(path, "/files/plugin")) {
            return true;
        }
    }

    // Dynamic code & plugins anywhere under private data
    if (Contains(path, "/files/sdk/plugin") ||
        Contains(path, "/sdk/plugin") ||
        Contains(path, "/plugin/") ||
        Contains(path, "/plugins/") ||
        Contains(path, "/app_plugin") ||
        Contains(path, "/app_plugins") ||
        Contains(path, "/app_dex") ||
        Contains(path, "/app_p_dex") ||
        Contains(path, "/dex/") ||
        EndsWith(path, "/dex") ||
        Contains(path, "/dalvik-cache")) {
        return true;
    }

    // Standard binaries, packages & code archives
    if (EndsWith(path, ".dex") || EndsWith(path, ".jar") || EndsWith(path, ".apk") ||
        EndsWith(path, ".odex") || EndsWith(path, ".vdex") || EndsWith(path, ".oat")) {
        return true;
    }

    // GMS, MicroG & authentication databases
    std::string lower = ToLower(path);
    if (Contains(lower, "com.google.android.gms") ||
        Contains(lower, "app.revanced.android.gms") ||
        Contains(lower, "org.microg") ||
        Contains(lower, "default_google_sign_in") ||
        Contains(lower, "google_app_measurement") ||
        Contains(lower, "games_signed_in") ||
        Contains(lower, "games_signin") ||
        Contains(lower, "playgames") ||
        Contains(lower, "google_sign_in") ||
        Contains(lower, "gservices") ||
        Contains(lower, "androidx.work.workdb") ||
        Contains(lower, "auth_state")) {
        return true;
    }

    return false;
}

bool PathTranslator::ToRealPath(const std::string& virtual_path, std::string& out_real_path) const {
    if (virtual_path.empty()) {
        out_real_path = virtual_path;
        return false;
    }

    std::string canonical = NormalizePath(virtual_path);

    std::lock_guard<std::mutex> lock(mutex_);

    // 1. Idempotency Guard for Custom Rules REAL Destinations:
    // If path is already inside a custom rule's real destination, return unchanged!
    for (const auto& rule : custom_rules_) {
        if (PathMatchesPrefix(canonical, rule.real_prefix)) {
            out_real_path = canonical;
            return false;
        }
    }

    // 1b. Ancestor Guard for Custom Rules REAL Destinations:
    // Parent directories of custom rule destinations must never be redirected to container_root_!
    if (IsAncestorOfRealPrefix(canonical)) {
        out_real_path = canonical;
        return false;
    }

    // 2. Custom Rules (HIGHEST PRECEDENCE - SUPERIOR TO CONTAINER & DEFAULT PATHS):
    for (const auto& rule : custom_rules_) {
        if (PathMatchesPrefix(canonical, rule.virtual_prefix)) {
            out_real_path = rule.real_prefix + canonical.substr(rule.virtual_prefix.length());
            return true;
        }
    }

    // 3. Shared storage alias canonicalization (/sdcard, /storage/self/primary, etc. -> /storage/emulated/0)
    std::string aliased = CanonicalizeAlias(canonical);
    if (aliased != canonical) {
        for (const auto& rule : custom_rules_) {
            if (PathMatchesPrefix(aliased, rule.real_prefix)) {
                out_real_path = aliased;
                return false;
            }
        }
        if (IsAncestorOfRealPrefix(aliased)) {
            out_real_path = aliased;
            return false;
        }
        for (const auto& rule : custom_rules_) {
            if (PathMatchesPrefix(aliased, rule.virtual_prefix)) {
                out_real_path = rule.real_prefix + aliased.substr(rule.virtual_prefix.length());
                return true;
            }
        }
    }

    // 4. Exemptions: native libraries and protected directories stay in real internal storage
    if (IsNativeLibLocation(canonical) || IsExempt(canonical) ||
        IsNativeLibLocation(aliased) || IsExempt(aliased)) {
        out_real_path = aliased;
        return false;
    }

    // 5. Container Idempotency Guard:
    // If path is already inside container_root_ (and didn't match a custom rule above):
    if (!container_root_.empty() && (PathMatchesPrefix(canonical, container_root_) || PathMatchesPrefix(aliased, container_root_))) {
        std::string c_path = PathMatchesPrefix(canonical, container_root_) ? canonical : aliased;
        // Check if this container path matches any custom rule!
        for (const auto& rule : custom_rules_) {
            if (PathMatchesPrefix(c_path, rule.virtual_prefix)) {
                out_real_path = rule.real_prefix + c_path.substr(rule.virtual_prefix.length());
                return true;
            }
        }
        // Unmap check for exemptions
        std::string c_data = container_root_ + "/data/data/" + package_name_;
        std::string c_data0 = container_root_ + "/data/user/0/" + package_name_;
        std::string internal_root = !internal_data_dir_.empty() ? internal_data_dir_ : ("/data/user/0/" + package_name_);
        std::string unmapped;
        if (PathMatchesPrefix(c_path, c_data)) {
            unmapped = NormalizePath(internal_root + c_path.substr(c_data.length()));
        } else if (PathMatchesPrefix(c_path, c_data0)) {
            unmapped = NormalizePath(internal_root + c_path.substr(c_data0.length()));
        }
        if (!unmapped.empty() && (IsNativeLibLocation(unmapped) || IsExempt(unmapped))) {
            out_real_path = unmapped;
            return true;
        }
        out_real_path = c_path;
        return false;
    }

    // 6. General Container Redirection (Default Fallback)
    std::string candidate_real;
    bool redirected = false;

    if (!container_root_.empty() && !package_name_.empty()) {
        // A. Internal Storage
        if (redirect_internal_) {
            std::string p1 = "/data/user/0/" + package_name_;
            std::string p2 = "/data/data/" + package_name_;

            if (PathMatchesPrefix(canonical, p1)) {
                candidate_real = container_root_ + "/data/data/" + package_name_ + canonical.substr(p1.length());
                redirected = true;
            } else if (PathMatchesPrefix(canonical, p2)) {
                candidate_real = container_root_ + "/data/data/" + package_name_ + canonical.substr(p2.length());
                redirected = true;
            }
        }

        // B. External Storage
        if (!redirected && redirect_external_) {
            const std::string sd0 = "/storage/emulated/0";

            std::string ext_app_data = sd0 + "/Android/data/" + package_name_;
            std::string ext_app_obb  = sd0 + "/Android/obb/" + package_name_;

            if (PathMatchesPrefix(aliased, ext_app_data)) {
                candidate_real = container_root_ + "/Android/data/" + package_name_ + aliased.substr(ext_app_data.length());
                redirected = true;
            } else if (PathMatchesPrefix(aliased, ext_app_obb)) {
                candidate_real = container_root_ + "/Android/obb/" + package_name_ + aliased.substr(ext_app_obb.length());
                redirected = true;
            } else if (PathMatchesPrefix(aliased, sd0 + "/Android/data") ||
                       PathMatchesPrefix(aliased, sd0 + "/Android/obb")) {
                out_real_path = aliased;
                return false;
            } else if (PathMatchesPrefix(aliased, sd0)) {
                candidate_real = container_root_ + aliased.substr(sd0.length());
                redirected = true;
            }
        }
    }

    if (redirected) {
        // Check if the candidate container path matches any custom rule!
        for (const auto& rule : custom_rules_) {
            if (PathMatchesPrefix(candidate_real, rule.virtual_prefix)) {
                out_real_path = rule.real_prefix + candidate_real.substr(rule.virtual_prefix.length());
                return true;
            }
        }
        out_real_path = candidate_real;
        return true;
    }

    // 7. Pass-through unmodified
    out_real_path = canonical;
    return false;
}

bool PathTranslator::ToVirtualPath(const std::string& real_path, std::string& out_virtual_path) const {
    if (real_path.empty()) {
        out_virtual_path = real_path;
        return false;
    }

    std::string canonical = NormalizePath(real_path);

    std::lock_guard<std::mutex> lock(mutex_);

    // 1. Reverse Custom Rules (longest real_prefix first)
    for (const auto& rule : custom_rules_) {
        if (PathMatchesPrefix(canonical, rule.real_prefix)) {
            std::string virt = rule.virtual_prefix + canonical.substr(rule.real_prefix.length());
            // If the custom rule's virtual prefix started with container_root_, unmap it to public shared path
            if (!container_root_.empty() && PathMatchesPrefix(virt, container_root_)) {
                std::string sub = virt.substr(container_root_.length());
                virt = NormalizePath("/storage/emulated/0" + sub);
            }
            out_virtual_path = virt;
            return true;
        }
    }

    // 2. Reverse General Container Redirection
    if (!container_root_.empty() && !package_name_.empty()) {
        std::string c_data = container_root_ + "/data/data/" + package_name_;
        if (PathMatchesPrefix(canonical, c_data)) {
            out_virtual_path = "/data/user/0/" + package_name_ + canonical.substr(c_data.length());
            return true;
        }

        std::string c_ext = container_root_ + "/Android/data/" + package_name_;
        if (PathMatchesPrefix(canonical, c_ext)) {
            out_virtual_path = "/storage/emulated/0/Android/data/" + package_name_ + canonical.substr(c_ext.length());
            return true;
        }

        std::string c_obb = container_root_ + "/Android/obb/" + package_name_;
        if (PathMatchesPrefix(canonical, c_obb)) {
            out_virtual_path = "/storage/emulated/0/Android/obb/" + package_name_ + canonical.substr(c_obb.length());
            return true;
        }

        // 3. Reverse Shared Storage (e.g. <container>/Download/... -> /storage/emulated/0/Download/...)
        if (PathMatchesPrefix(canonical, container_root_)) {
            out_virtual_path = "/storage/emulated/0" + canonical.substr(container_root_.length());
            return true;
        }
    }

    out_virtual_path = canonical;
    return false;
}

} // namespace lspd::storage
