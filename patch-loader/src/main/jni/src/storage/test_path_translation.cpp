#include "path_translation.h"

#include <iostream>
#include <cassert>

using namespace lspd::storage;

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            std::cerr << "FAILED: " << msg << " at line " << __LINE__ << std::endl; \
            return 1; \
        } else { \
            std::cout << "  [PASS] " << msg << std::endl; \
        } \
    } while (0)

int main() {
    std::cout << "Running PathTranslator Unit Tests..." << std::endl;

    // 1. Normalization & Traversal tests
    std::cout << "Test Suite 1: Path Normalization & Traversal" << std::endl;
    TEST_ASSERT(PathTranslator::NormalizePath("/data/data/com.test/files/../../../../etc/hosts") == "/etc/hosts",
                "Traversal escaping sandbox (/data/data) reduces strictly to /etc/hosts");
    TEST_ASSERT(PathTranslator::NormalizePath("/data/user/0/com.test/files/../../../../../etc/hosts") == "/etc/hosts",
                "Traversal escaping sandbox (/data/user/0) reduces strictly to /etc/hosts");
    TEST_ASSERT(PathTranslator::NormalizePath("/data/user/0/com.test/files/../databases/app.db") == "/data/user/0/com.test/databases/app.db",
                "Internal traversal within sandbox resolves to databases/app.db");
    TEST_ASSERT(PathTranslator::NormalizePath("files/../../../../etc/hosts") == "../../../etc/hosts",
                "Relative path traversal preserves leading .. without collapsing across root");
    TEST_ASSERT(PathTranslator::NormalizePath("a//b/./c/../d") == "a/b/d",
                "Duplicate slashes and . / .. collapsed cleanly");
    TEST_ASSERT(PathTranslator::NormalizePath("/") == "/", "Root path normalizes to /");
    TEST_ASSERT(PathTranslator::NormalizePath("") == "", "Empty path normalizes to empty");

    // 2. Setup PathTranslator Instance
    std::cout << "Test Suite 2: Setup & Container Initialization" << std::endl;
    auto& translator = PathTranslator::Instance();
    std::string container = "/storage/emulated/0/.fnrod";
    std::string pkg = "com.test.app";
    std::string internal_dir = "/data/user/0/com.test.app";
    translator.Init(container, pkg, internal_dir);

    TEST_ASSERT(translator.ContainerRoot() == container, "Container root initialized");
    TEST_ASSERT(translator.PackageName() == pkg, "Package name initialized");
    TEST_ASSERT(translator.InternalDataDir() == internal_dir, "Internal data dir initialized");

    // 3. General Container Redirection
    std::cout << "Test Suite 3: General Container Redirection" << std::endl;
    std::string real;
    bool redirected = translator.ToRealPath("/data/user/0/com.test.app/files/data.txt", real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/.fnrod/data/data/com.test.app/files/data.txt",
                "Internal files redirected to container data/data");

    redirected = translator.ToRealPath("/data/data/com.test.app/databases/app.db", real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/.fnrod/data/data/com.test.app/databases/app.db",
                "Internal /data/data databases redirected to container data/data");

    redirected = translator.ToRealPath("/storage/emulated/0/Android/data/com.test.app/files/log.txt", real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/.fnrod/Android/data/com.test.app/files/log.txt",
                "External shared files redirected to container Android/data");

    // 4. Idempotency Guard (No Double Translation)
    std::cout << "Test Suite 4: Idempotency Guard" << std::endl;
    std::string already_real = "/storage/emulated/0/.fnrod/data/data/com.test.app/files/data.txt";
    redirected = translator.ToRealPath(already_real, real);
    TEST_ASSERT(!redirected && real == already_real, "Already-real container path passes through untouched");

    // 5. Native Library Exemption (Directory-Level)
    std::cout << "Test Suite 5: Native Library Exemption" << std::endl;
    std::string so_file = "/data/user/0/com.test.app/files/decompressed/libs.spo/libtest.so";
    redirected = translator.ToRealPath(so_file, real);
    TEST_ASSERT(!redirected && real == so_file, ".so file is exempt and stays in real internal storage");

    translator.AddNativeLibDir("/data/user/0/com.test.app/files/decompressed/libs.spo");
    std::string non_so_in_lib_dir = "/data/user/0/com.test.app/files/decompressed/libs.spo/metadata.spo";
    redirected = translator.ToRealPath(non_so_in_lib_dir, real);
    TEST_ASSERT(!redirected && real == non_so_in_lib_dir,
                "Non-.so file inside native lib directory stays in real internal storage");

    // 6. Anchored Core Exemptions
    std::cout << "Test Suite 6: Anchored Core Exemptions" << std::endl;
    std::string webview_dir = "/data/user/0/com.test.app/app_webview/Default";
    redirected = translator.ToRealPath(webview_dir, real);
    TEST_ASSERT(!redirected && real == webview_dir, "Anchored app_webview stays in real internal storage");

    std::string lspatch_dir = "/data/user/0/com.test.app/app_lspatch/config";
    redirected = translator.ToRealPath(lspatch_dir, real);
    TEST_ASSERT(!redirected && real == lspatch_dir, "Anchored app_lspatch stays in real internal storage");

    std::string fake_webview = "/data/user/0/com.test.app/files/app_webview_data";
    redirected = translator.ToRealPath(fake_webview, real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/.fnrod/data/data/com.test.app/files/app_webview_data",
                "Non-anchored substring /files/app_webview_data is NOT exempt and redirects");

    // 7. Custom Rules with {pkg} Expansion & Longest Prefix First
    std::cout << "Test Suite 7: Custom Rules & Longest-Prefix Matching" << std::endl;
    CustomRule rule_short{
        .virtual_prefix = "/storage/emulated/0/Android/data/{pkg}/files/games",
        .real_prefix = "/storage/emulated/0/games",
        .flags = 0
    };
    CustomRule rule_long{
        .virtual_prefix = "/storage/emulated/0/Android/data/{pkg}/files/games/special",
        .real_prefix = "/storage/emulated/0/special_games",
        .flags = 0
    };

    // Add shorter first, then longer to test sorting
    translator.AddCustomRule(rule_short);
    translator.AddCustomRule(rule_long);

    std::string normal_game = "/storage/emulated/0/Android/data/com.test.app/files/games/world1/level.dat";
    redirected = translator.ToRealPath(normal_game, real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/games/world1/level.dat",
                "Custom rule correctly maps games folder to /storage/emulated/0/games");

    std::string special_game = "/storage/emulated/0/Android/data/com.test.app/files/games/special/level.dat";
    redirected = translator.ToRealPath(special_game, real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/special_games/level.dat",
                "Longest-prefix rule wins for games/special");

    // 8. Custom Rule Idempotency & Direct Real Path Access
    std::cout << "Test Suite 8: Custom Rule Idempotency & Direct Real Access" << std::endl;
    std::string direct_real = "/storage/emulated/0/games/world1/level.dat";
    redirected = translator.ToRealPath(direct_real, real);
    TEST_ASSERT(!redirected && real == direct_real,
                "Direct query to custom rule real destination passes through untouched (idempotency guard)");

    // 9. Reverse Path Virtualization (ToVirtualPath)
    std::cout << "Test Suite 9: Reverse Path Virtualization" << std::endl;
    std::string virt;
    bool reversed = translator.ToVirtualPath("/storage/emulated/0/games/world1/level.dat", virt);
    TEST_ASSERT(reversed && virt == "/storage/emulated/0/Android/data/com.test.app/files/games/world1/level.dat",
                "Reverse mapping converts real destination back to virtual path");

    reversed = translator.ToVirtualPath("/storage/emulated/0/special_games/level.dat", virt);
    TEST_ASSERT(reversed && virt == "/storage/emulated/0/Android/data/com.test.app/files/games/special/level.dat",
                "Reverse mapping converts special destination back to virtual path");

    reversed = translator.ToVirtualPath("/storage/emulated/0/.fnrod/data/data/com.test.app/files/data.txt", virt);
    TEST_ASSERT(reversed && virt == "/data/user/0/com.test.app/files/data.txt",
                "Reverse mapping converts container data path back to virtual path");

    reversed = translator.ToVirtualPath("/storage/emulated/0/.fnrod/Android/data/com.test.app/files/log.txt", virt);
    TEST_ASSERT(reversed && virt == "/storage/emulated/0/Android/data/com.test.app/files/log.txt",
                "Reverse mapping converts container external path back to virtual path");

    // 10. Container Subfolder Custom Rule & Ancestor Protection
    std::cout << "Test Suite 10: Container Subfolder Custom Rule & Ancestor Protection" << std::endl;
    CustomRule rule_download{
        .virtual_prefix = "/storage/emulated/0/.fnrod/Download",
        .real_prefix = "/storage/emulated/0/.gxr/Download",
        .flags = 0
    };
    translator.AddCustomRule(rule_download);

    // Test virtual path /storage/emulated/0/Download -> /storage/emulated/0/.gxr/Download
    redirected = translator.ToRealPath("/storage/emulated/0/Download/file.pdf", real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/.gxr/Download/file.pdf",
                "Virtual /storage/emulated/0/Download routes to custom rule destination /storage/emulated/0/.gxr/Download");

    // Test alias /sdcard/Download -> /storage/emulated/0/.gxr/Download
    redirected = translator.ToRealPath("/sdcard/Download/file.pdf", real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/.gxr/Download/file.pdf",
                "Alias /sdcard/Download routes to custom rule destination /storage/emulated/0/.gxr/Download");

    // Test container path /storage/emulated/0/.fnrod/Download -> /storage/emulated/0/.gxr/Download
    redirected = translator.ToRealPath("/storage/emulated/0/.fnrod/Download/file.pdf", real);
    TEST_ASSERT(redirected && real == "/storage/emulated/0/.gxr/Download/file.pdf",
                "Direct container path /storage/emulated/0/.fnrod/Download routes to custom rule destination /storage/emulated/0/.gxr/Download");

    // Test ancestor protection: /storage/emulated/0/.gxr must NOT be redirected to .fnrod/.gxr
    redirected = translator.ToRealPath("/storage/emulated/0/.gxr", real);
    TEST_ASSERT(!redirected && real == "/storage/emulated/0/.gxr",
                "Ancestor directory /storage/emulated/0/.gxr is NOT redirected to container (ancestor guard)");

    // Test reverse mapping: /storage/emulated/0/.gxr/Download/file.pdf -> /storage/emulated/0/Download/file.pdf
    reversed = translator.ToVirtualPath("/storage/emulated/0/.gxr/Download/file.pdf", virt);
    TEST_ASSERT(reversed && virt == "/storage/emulated/0/Download/file.pdf",
                "Reverse mapping converts /storage/emulated/0/.gxr/Download cleanly to /storage/emulated/0/Download");

    std::cout << "\nALL 25 UNIT TESTS PASSED SUCCESSFULLY!" << std::endl;
    return 0;
}
