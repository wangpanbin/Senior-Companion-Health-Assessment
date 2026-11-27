# -*- coding: utf-8 -*-
"""
把 docs/reports/C23-迭代2-精修报告.md 填入课程报告模板，产出 docx。

用法：
    python tools/report/build_c23_docx.py

约定：
  - 模板封面（评阅表 / 学院名 / 校徽 / 组长组员表）**原样保留**，只替换占位文字；
  - 正文从「1．团队分工」起整体重建，样式统一（宋体小四、1.5 倍行距、首行缩进 2 字符）；
  - 图片按长宽比自动定宽：横图 14cm，移动端竖图 5.6cm；图注居中五号；
  - 姓名/学号/团队照片保留【】占位，便于最后一次性替换。
"""
import os
import re
import sys
from pathlib import Path

import docx
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.shared import Cm, Pt, RGBColor
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path(r"C:\Users\wang\OneDrive\桌面\C23 迭代2 精修 模板.docx")
MD = ROOT / "docs" / "reports" / "C23-迭代2-精修报告.md"
IMG_DIR = ROOT / "docs" / "reports" / "assets" / "c23-iter2"
OUT = ROOT / "docs" / "reports" / "C23-迭代2-精修报告-银龄伴诊.docx"

BODY_FONT = "宋体"
HEAD_FONT = "黑体"


def set_run(run, size=12, bold=False, font=BODY_FONT, color=None):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.name = font
    run._element.rPr.rFonts.set(qn("w:eastAsia"), font)
    if color:
        run.font.color.rgb = RGBColor(*color)


def style_para(p, first_indent=True, align=WD_ALIGN_PARAGRAPH.JUSTIFY, space_after=6):
    pf = p.paragraph_format
    pf.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
    pf.space_after = Pt(space_after)
    pf.space_before = Pt(0)
    pf.alignment = align
    if first_indent:
        pf.first_line_indent = Cm(0.74)


INLINE_RE = re.compile(r"(\*\*.+?\*\*|`[^`]+`)")


def _has_page_break(p):
    for br in p._element.iter(qn("w:br")):
        if br.get(qn("w:type")) == "page":
            return True
    return False


def add_inline(p, text, size=12, base_bold=False):
    """处理 **加粗** 与 `代码`"""
    for seg in INLINE_RE.split(text):
        if not seg:
            continue
        if seg.startswith("**") and seg.endswith("**"):
            r = p.add_run(seg[2:-2])
            set_run(r, size, bold=True)
        elif seg.startswith("`") and seg.endswith("`"):
            r = p.add_run(seg[1:-1])
            set_run(r, max(size - 1.5, 9), bold=base_bold, font="Consolas")
        else:
            r = p.add_run(seg)
            set_run(r, size, bold=base_bold)


def _set_table_borders(t):
    """模板里没有 'Table Grid' 样式，手工加单线边框（三线表风格退化为细网格）。"""
    tbl = t._tbl
    tblPr = tbl.tblPr
    borders = OxmlElement("w:tblBorders")
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        el = OxmlElement(f"w:{edge}")
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), "4")
        el.set(qn("w:space"), "0")
        el.set(qn("w:color"), "000000")
        borders.append(el)
    tblPr.append(borders)


def add_table(doc, rows):
    header, body = rows[0], rows[1:]
    t = doc.add_table(rows=1 + len(body), cols=len(header))
    try:
        t.style = "Table Grid"
    except KeyError:
        _set_table_borders(t)
    for j, txt in enumerate(header):
        cell = t.rows[0].cells[j]
        cell.text = ""
        p = cell.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        add_inline(p, txt, size=10.5, base_bold=True)
    # 表头跨页重复，长表分页后仍能看清列含义
    trPr = t.rows[0]._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    trPr.append(tbl_header)
    for i, row in enumerate(body, start=1):
        for j in range(len(header)):
            cell = t.rows[i].cells[j]
            cell.text = ""
            p = cell.paragraphs[0]
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            add_inline(p, row[j] if j < len(row) else "", size=10.5)
    doc.add_paragraph().paragraph_format.space_after = Pt(0)


def add_figure(doc, filename, caption):
    path = IMG_DIR / filename
    if not path.exists():
        print(f"  ! 缺图：{path}")
        return
    docx_img = doc.add_picture(str(path))
    width_cm = 14.0
    try:
        from PIL import Image
        with Image.open(path) as im:
            w, h = im.size
        ratio = w / h
        width_cm = 14.0 if ratio >= 0.9 else 5.6
        if ratio >= 0.9 and ratio < 1.3:
            width_cm = 12.0
        docx_img.width = Cm(width_cm)
        docx_img.height = Cm(width_cm * h / w)
    except Exception:
        docx_img.width = Cm(width_cm)
    p = doc.paragraphs[-1]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(2)
    p.paragraph_format.keep_together = True
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.SINGLE

    cp = doc.add_paragraph()
    cp.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cp.paragraph_format.space_after = Pt(12)
    r = cp.add_run(caption)
    set_run(r, 10.5)


def parse_md():
    text = MD.read_text(encoding="utf-8")
    blocks = []
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip():
            i += 1
            continue
        if line.strip().startswith("```"):
            i += 1
            code = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code.append(lines[i].rstrip())
                i += 1
            i += 1
            blocks.append(("code", code))
            continue
        if line.strip() in ("---", "***", "___"):
            i += 1
            continue
        if line.startswith("[IMG:"):
            m = re.match(r"\[IMG:(.+?)\|(.+?)\]", line)
            if m:
                blocks.append(("img", (m.group(1), m.group(2))))
            i += 1
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                raw = lines[i].strip().strip("|")
                cells = [c.strip() for c in raw.split("|")]
                if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            blocks.append(("table", rows))
            continue
        if line.startswith("### "):
            blocks.append(("h3", line[4:].strip()))
            i += 1
            continue
        if line.startswith("## "):
            blocks.append(("h2", line[3:].strip()))
            i += 1
            continue
        if line.startswith("# "):
            blocks.append(("h1", line[2:].strip()))
            i += 1
            continue
        if line.startswith("- "):
            blocks.append(("li", line[2:].strip()))
            i += 1
            continue
        if re.match(r"^\d+\.\s", line):
            blocks.append(("ol", re.sub(r"^\d+\.\s", "", line)))
            i += 1
            continue
        # 段落：合并连续非空行
        buf = [line.strip()]
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r"^(#|\||\[IMG|-\s|\d+\.\s|```)", lines[i].strip()):
            buf.append(lines[i].strip())
            i += 1
        blocks.append(("p", " ".join(buf)))
    return blocks


def _fill_body_names(doc):
    """封面被人工填好后，正文分工表不再留【】占位：组长→组长姓名，组员按封面顺序（跳过与组长重复的）"""
    if len(doc.tables) < 2:
        return
    cover = {}
    for row in doc.tables[1].rows:
        cells = [c.text.strip() for c in row.cells]
        if len(cells) == 2 and cells[0].endswith("："):
            cover[cells[0]] = " ".join(cells[1].split())
    leader = cover.get("组长姓名：", "")
    if "【" in leader or not leader:
        return
    members = []
    for i in range(1, 5):
        v = cover.get(f"组员{i}：", "")
        if v and "【" not in v and v.split()[0] != leader:
            members.append(v)

    def replace(old, new):
        for p in doc.paragraphs:
            if old in p.text:
                for r in p.runs:
                    if old in r.text:
                        r.text = r.text.replace(old, new)
                return True
        for t in doc.tables:
            for row in t.rows:
                for cell in row.cells:
                    for p in cell.paragraphs:
                        if old in p.text:
                            for r in p.runs:
                                if old in r.text:
                                    r.text = r.text.replace(old, new)
                            return True
        return False

    replace("【组长姓名】", " " + leader)
    for idx in range(3):
        if not members:
            break
        replace("【组员姓名/学号】", " " + members[idx])


def load_cover_overrides():
    """已经交付过一次：若旧 docx 封面上已由人工填了真实信息（不含【占位】），就原样保留。"""
    if not OUT.exists():
        return {}
    try:
        old = docx.Document(str(OUT))
        t = old.tables[1]
    except Exception:
        return {}
    out = {}
    for row in t.rows:
        cells = [c.text.strip() for c in row.cells]
        if len(cells) != 2:
            continue
        label, value = cells
        if value and "【" not in value and "】" not in value and label.endswith("："):
            out[label] = value
    return out


def main():
    # 模板可能已被替换成成品：模板不在时，以已生成的 docx 自身为底板重建正文（封面/校徽/版式保留）
    tpl = TEMPLATE if TEMPLATE.exists() else OUT
    if not tpl.exists():
        sys.exit(f"既没有模版也没有成品可作底板：{TEMPLATE} / {OUT}")
    work = tpl
    if tpl == OUT:
        import shutil
        work = OUT.with_suffix(".rebuild.docx")
        shutil.copy(OUT, work)
        print(f"模版缺失，改用成品自身作底板：{OUT.name}")

    doc = docx.Document(str(work))
    cover_overrides = load_cover_overrides()

    # ---------- 1. 封面表替换 ----------
    member_idx = 1
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                for p in cell.paragraphs:
                    txt = p.text.strip()
                    new = None
                    if txt == "例：软件工程2024级Java方向-1班":
                        new = "【软件工程2024级Java方向-1班】"
                    elif txt == "组长的 姓名":
                        new = "【组长姓名】"
                    elif txt == "组长的 学号":
                        new = "【组长学号】"
                    elif txt == "小组成员1的 姓名、学号":
                        new = f"【组员{member_idx}姓名 / 学号】"
                        member_idx += 1
                    if new:
                        # 人工已填过的值优先（按同标签的值覆盖）
                        row_cells = [c.text.strip() for c in row.cells]
                        for lbl, val in cover_overrides.items():
                            if row_cells and row_cells[0] == lbl:
                                new = val
                                break
                        if p.runs:
                            p.runs[0].text = new
                            for r in p.runs[1:]:
                                r.text = ""
                        else:
                            p.add_run(new)

    # ---------- 2. 删模板提示语 ----------
    for p in doc.paragraphs:
        if p.text.strip().startswith("本模版仅供参考") or p.text.strip().startswith("请用心编写"):
            p._element.getparent().remove(p._element)

    # ---------- 3. 题目 / 摘要 / 关键词 ----------
    blocks = parse_md()
    title = next(b[1] for b in blocks if b[0] == "h1")
    body_blocks = [b for b in blocks if b[0] != "h1"]
    abstract = next(b[1] for b in body_blocks if b[0] == "p" and b[1].startswith("「银龄伴诊」"))
    # 摘要与关键词：取 markdown 里显式写出的两条（h2 摘要 之后的第一段）
    abs_text, kw_text = None, None
    for idx, b in enumerate(blocks):
        if b[0] == "h2" and b[1].startswith("摘要"):
            abs_text = blocks[idx + 1][1]
        if b[0] == "p" and b[1].startswith("关键词："):
            kw_text = b[1]

    # 题目
    para_title = None
    for p in doc.paragraphs:
        if p.text.strip().startswith("题目") or p.text.strip().startswith("「银龄伴诊」"):
            para_title = p
            break
    if para_title is not None:
        para_title.text = ""
        para_title.alignment = WD_ALIGN_PARAGRAPH.CENTER
        r = para_title.add_run("「银龄伴诊」")
        set_run(r, 16, bold=True, font=HEAD_FONT)
        r2 = para_title.add_run("\n老年人就医陪诊与用药协同管理平台")
        set_run(r2, 14, bold=True, font=HEAD_FONT)
        para_title.paragraph_format.space_after = Pt(18)

    for p in doc.paragraphs:
        if p.text.strip().startswith("摘要："):
            p.text = ""
            style_para(p, first_indent=False)
            r = p.add_run("摘要：")
            set_run(r, 12, bold=True)
            add_inline(p, abs_text or "")
        if p.text.strip().startswith("关键词："):
            p.text = ""
            style_para(p, first_indent=False)
            r = p.add_run("关键词：")
            set_run(r, 12, bold=True)
            add_inline(p, (kw_text or "").replace("关键词：", ""))

    # ---------- 4. 删掉 1~7 节所有模板正文 ----------
    start = None
    for p in doc.paragraphs:
        if p.text.strip().startswith("1．团队分工"):
            start = p
            break
    if start is None:
        sys.exit("未找到『1．团队分工』，模板结构变了")
    body = start._element.getparent()
    started = False
    for el in list(body.iterchildren()):
        if el is start._element:
            started = True
        if started and el.tag != qn("w:sectPr"):
            body.remove(el)

    # ---------- 5. 重建 1~7 节 ----------
    if not (doc.paragraphs and _has_page_break(doc.paragraphs[-1])):
        doc.add_page_break()
    first_h2 = True
    skip_abs = True
    for kind, val in body_blocks:
        if kind == "h2":
            if val.startswith("摘要"):
                continue
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(0 if first_h2 else 12)
            p.paragraph_format.space_after = Pt(8)
            p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
            add_inline(p, val, size=15, base_bold=True)
            for r in p.runs:
                r.font.name = HEAD_FONT
                r._element.rPr.rFonts.set(qn("w:eastAsia"), HEAD_FONT)
            first_h2 = False
        elif kind == "h3":
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(10)
            p.paragraph_format.space_after = Pt(4)
            p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
            add_inline(p, val, size=12, base_bold=True)
            for r in p.runs:
                r.font.name = HEAD_FONT
                r._element.rPr.rFonts.set(qn("w:eastAsia"), HEAD_FONT)
        elif kind == "p":
            if val.startswith("关键词："):
                continue
            if skip_abs:
                skip_abs = False
                continue
            if val.strip() == "（团队合影与登台报告照片：见文末插图位，由团队提供。）":
                for hint in ("【此处插入团队合影】", "【此处插入登台报告照片】"):
                    hp = doc.add_paragraph()
                    hp.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    hp.paragraph_format.space_before = Pt(8)
                    hp.paragraph_format.space_after = Pt(8)
                    r = hp.add_run(hint)
                    set_run(r, 12, color=(0x88, 0x88, 0x88))
                continue
            p = doc.add_paragraph()
            style_para(p)
            add_inline(p, val)
        elif kind == "li":
            p = doc.add_paragraph()
            style_para(p, first_indent=False, align=WD_ALIGN_PARAGRAPH.JUSTIFY)
            p.paragraph_format.left_indent = Cm(0.74)
            add_inline(p, "· " + val)
        elif kind == "ol":
            p = doc.add_paragraph()
            style_para(p, first_indent=False, align=WD_ALIGN_PARAGRAPH.JUSTIFY)
            p.paragraph_format.left_indent = Cm(0.74)
            add_inline(p, val)
        elif kind == "code":
            p = doc.add_paragraph()
            pf = p.paragraph_format
            pf.line_spacing_rule = WD_LINE_SPACING.SINGLE
            pf.space_before = Pt(4)
            pf.space_after = Pt(8)
            pf.left_indent = Cm(0.4)
            pf.alignment = WD_ALIGN_PARAGRAPH.LEFT
            r = p.add_run("\n".join(val))
            set_run(r, 9, font="Consolas")
        elif kind == "table":
            add_table(doc, val)
        elif kind == "img":
            add_figure(doc, val[0], val[1])

    # 段落之间留白：中文排版习惯，空行由 space_after 控制，不再插空段
    # ---------- 6. 封面已填真实姓名时，顺手把正文分工表里的占位符也填上 ----------
    _fill_body_names(doc)

    # 结尾两段保持成块，避免最后几行被甩到一个几乎空白的尾页
    tail = [p for p in doc.paragraphs if p.text.strip()][-3:]
    for p in tail:
        p.paragraph_format.keep_together = True
    for p in tail[:-1]:
        p.paragraph_format.keep_with_next = True

    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc.save(str(OUT))
    print(f"已生成：{OUT}")


if __name__ == "__main__":
    main()

