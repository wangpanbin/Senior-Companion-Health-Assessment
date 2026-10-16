import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS = Path(__file__).resolve().parent


class EntityGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.backend = Path(self.temp.name) / 'backend'
        self.tools = self.backend / 'sql' / 'tools'
        self.tools.mkdir(parents=True)
        shutil.copy2(TOOLS / 'gen_entity.py', self.tools)
        for source in TOOLS.parent.glob('V*.sql'):
            shutil.copy2(source, self.tools.parent)
        self.java = self.backend / 'src/main/java/org/company/nianglin'
        for folder in ('entity', 'mapper'):
            (self.java / folder).mkdir(parents=True)
        self.sentinel = self.java / 'mapper/ChatQueryMapper.java'
        self.sentinel.write_text('custom mapper must survive', encoding='utf-8')
        self.existing = self.java / 'entity/SysUser.java'
        self.existing.write_text('unselected entity must survive', encoding='utf-8')

    def generate(self, *args):
        return subprocess.run([sys.executable, '-X', 'utf8', str(self.tools / 'gen_entity.py'), *args],
                              capture_output=True, text=True, encoding='utf-8')

    def test_database_docs_include_incremental_tables(self):
        shutil.copy2(TOOLS / 'gen_db_docs.py', self.tools)
        result = subprocess.run(
            [sys.executable, '-X', 'utf8', str(self.tools / 'gen_db_docs.py'), 'render'],
            env={**os.environ, 'TMP': self.temp.name, 'TEMP': self.temp.name},
            capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(0, result.returncode, result.stderr)
        docs = Path(self.temp.name) / 'docs/db'
        dictionary = (docs / '02-数据字典.md').read_text(encoding='utf-8')
        overview = (docs / 'README.md').read_text(encoding='utf-8')
        seed = (docs / '04-种子数据说明.md').read_text(encoding='utf-8')
        self.assertIn('`chat_message`', dictionary)
        self.assertIn('`chat_read_cursor`', dictionary)
        self.assertIn('`M13`', dictionary)
        self.assertIn('V5__chat_message.sql', overview)
        self.assertIn('运行库快照', seed)
        self.assertNotIn('业务表每张 **≥ 30 行**', seed)

    def test_selected_incremental_tables_preserve_other_files(self):
        result = self.generate('--tables', 'chat_message', 'chat_read_cursor')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('custom mapper must survive', self.sentinel.read_text(encoding='utf-8'))
        self.assertEqual('unselected entity must survive', self.existing.read_text(encoding='utf-8'))
        message = (self.java / 'entity/ChatMessage.java').read_text(encoding='utf-8')
        cursor = (self.java / 'entity/ChatReadCursor.java').read_text(encoding='utf-8')
        self.assertIn('private String fileId;', message)
        self.assertIn('private Long fileSize;', message)
        self.assertIn('private LocalDateTime readTime;', cursor)
        self.assertTrue((self.java / 'mapper/ChatMessageMapper.java').exists())
        self.assertFalse((self.java / 'mapper/SysUserMapper.java').exists())

    def test_missing_selection_does_not_write(self):
        result = self.generate()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('custom mapper must survive', self.sentinel.read_text(encoding='utf-8'))
        self.assertEqual(1, len(list((self.java / 'entity').glob('*.java'))))

    def test_unknown_table_fails_before_any_write(self):
        result = self.generate('--tables', 'chat_message', 'unknown_table')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.java / 'entity/ChatMessage.java').exists())
        self.assertTrue(self.sentinel.exists())


if __name__ == '__main__':
    unittest.main()
