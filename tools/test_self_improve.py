import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import self_improve as si

KT = "package com.nova.assistant\n\nobject X { val a = 1 }\n"

class Guard(unittest.TestCase):
    def refused(self, path, content=KT):
        with self.assertRaises(ValueError):
            si.check_patch({"files": [{"path": path, "content": content}]})

    def test_protected_paths(self):
        base = "app/src/main/java/com/nova/assistant/"
        for n in ["SecureStore.kt", "Control.kt", "Updater.kt", "UpdateManager.kt", "InstallReceiver.kt"]:
            self.refused(base + n)
        for p in ["app/src/main/AndroidManifest.xml", "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                  ".github/workflows/build.yml", "app/src/test/java/com/nova/assistant/LogicTest.kt",
                  "tools/self_improve.py", "feed/feed.json", "gradle.properties"]:
            self.refused(p)

    def test_bad_paths(self):
        for p in ["../x.kt", "/etc/passwd", "app/src/main/java/com/nova/assistant/../Control.kt", "random.kt", "", "a\\b.kt"]:
            self.refused(p)

    def test_allowed(self):
        files, _ = si.check_patch({"summary": "s", "files": [{"path": "app/src/main/java/com/nova/assistant/Brain.kt", "content": KT}]})
        self.assertEqual(files[0][0], "app/src/main/java/com/nova/assistant/Brain.kt")
        si.check_patch({"files": [{"path": "app/src/main/assets/ui.html", "content": "<html></html>"}]})
        si.check_patch({"files": [{"path": "skillpacks/x.json", "content": '{"skills": []}'}]})

    def test_limits(self):
        p = "app/src/main/java/com/nova/assistant/%s.kt"
        with self.assertRaises(ValueError): si.check_patch({"files": []})
        with self.assertRaises(ValueError):
            si.check_patch({"files": [{"path": p % n, "content": KT} for n in "ABCD"]})
        with self.assertRaises(ValueError):
            si.check_patch({"files": [{"path": p % "A", "content": KT}, {"path": p % "A", "content": KT}]})
        self.refused(p % "A", KT + "x" * (si.MAX_FILE_BYTES + 1))

    def test_content_rules(self):
        p = "app/src/main/java/com/nova/assistant/Brain.kt"
        self.refused(p, KT + "val k = \"AIza" + "a" * 35 + "\"\n")
        self.refused(p, KT + "val x = y!!\n")
        self.refused(p, KT + "try { } catch (_: Exception) { }\n")
        self.refused(p, "object X {}\n")
        self.refused("skillpacks/x.json", "{not json")

if __name__ == "__main__":
    unittest.main()
