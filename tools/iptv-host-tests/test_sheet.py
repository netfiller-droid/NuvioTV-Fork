import argparse, html, re
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

ap = argparse.ArgumentParser(description='Printable device test sheet from IPTV-DEVICE-TEST-CHECKLIST.md')
ap.add_argument('checklist')
ap.add_argument('out')
ap.add_argument('--build', required=True)
ap.add_argument('--run', required=True)
ap.add_argument('--date', required=True)
ap.add_argument('--sections', required=True, help='newest first, e.g. 7m,7l,7k')
ap.add_argument('--font-dir', default='/usr/share/fonts/truetype/dejavu')
a = ap.parse_args()

pdfmetrics.registerFont(TTFont('DV', f'{a.font_dir}/DejaVuSans.ttf'))
pdfmetrics.registerFont(TTFont('DVB', f'{a.font_dir}/DejaVuSans-Bold.ttf'))
text = open(a.checklist, encoding='utf-8').read() + '\n## end\n'
wanted = [s.strip() for s in a.sections.split(',') if s.strip()]
sections = []
for key in wanted:
    m = re.search(r'^## ' + re.escape(key) + r'\. (.*?)$\n(.*?)(?=^## )', text, re.S | re.M)
    if not m:
        raise SystemExit(f'section {key} not found')
    title = re.sub(r'\s*\(build `?[0-9a-f]+`?[^)]*\)', '', m.group(1)).replace('`', '')
    items, cur = [], None
    for line in m.group(2).splitlines():
        if not line.strip():
            continue
        n = re.match(r'^(\d+)\. (.*)$', line)
        if n:
            cur = [n.group(1), n.group(2)]
            items.append(cur)
        elif line.startswith('   ') and cur is not None:
            cur[1] += ' ' + line.strip()
        else:
            items.append(['', line.strip().rstrip(':')])
            cur = None
    sections.append((key, title, items))

base = ParagraphStyle('b', fontName='DV', fontSize=8.6, leading=11)
small = ParagraphStyle('s', parent=base, fontSize=7.8, leading=10, textColor=colors.HexColor('#444444'))
h1 = ParagraphStyle('h1', fontName='DVB', fontSize=16, leading=20, spaceAfter=2)
h2 = ParagraphStyle('h2', fontName='DVB', fontSize=11, leading=14, spaceBefore=8, spaceAfter=4)
sub = ParagraphStyle('sub', fontName='DVB', fontSize=8.6, leading=11, textColor=colors.HexColor('#333333'))
hdr = ParagraphStyle('hdr', fontName='DVB', fontSize=7.4, leading=9, alignment=1)


def esc(s):
    return html.escape(s.replace('`', ''), quote=False)


story = [Paragraph('Nuvio IPTV Prototype — device test sheet', h1),
         Paragraph(esc(f'Build {a.build} · CI run {a.run} · {a.date}. App "Nuvio IPTV Prototype", file app-iptvPrototype-arm64-v8a-debug.apk.'), small),
         Spacer(1, 4),
         Paragraph(esc('Tick Pass, Fail or Not tested (N/T) for each line and jot what happened in Notes. Sections run newest first; '
                       f'{wanted[0]} is new in this build. For failures, a photo of the screen helps. Do not share logs that contain addresses.'), small),
         Spacer(1, 2),
         Paragraph(esc('Tester: ____________________   TV / box: ____________________   Resolution:  ☐ 4K   ☐ 1080p'), base),
         Spacer(1, 6)]
widths = [8 * mm, 100 * mm, 10 * mm, 10 * mm, 10 * mm, 42 * mm]
for key, title, items in sections:
    rows = [[Paragraph(h, hdr) for h in ('#', 'Check', 'Pass', 'Fail', 'N/T', 'Notes')]]
    style = [('GRID', (0, 0), (-1, -1), 0.4, colors.HexColor('#9a9a9a')),
             ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#e8e8e8')),
             ('VALIGN', (0, 0), (-1, -1), 'TOP'),
             ('LEFTPADDING', (0, 0), (-1, -1), 3), ('RIGHTPADDING', (0, 0), (-1, -1), 3),
             ('TOPPADDING', (0, 0), (-1, -1), 3), ('BOTTOMPADDING', (0, 0), (-1, -1), 5)]
    for num, body in items:
        r = len(rows)
        if not num:
            rows.append([Paragraph(esc(body), sub), '', '', '', '', ''])
            style += [('SPAN', (0, r), (-1, r)), ('BACKGROUND', (0, r), (-1, r), colors.HexColor('#f4f4f4'))]
        else:
            rows.append([Paragraph(esc(num), base), Paragraph(esc(body), base), '', '', '', ''])
    table = Table(rows, colWidths=widths, repeatRows=1)
    table.setStyle(TableStyle(style))
    story += [Paragraph(esc(f'{key}. {title}'), h2), table]
story.append(Paragraph('Anything else noticed', h2))
lines = Table([[''] for _ in range(5)], colWidths=[180 * mm], rowHeights=[8 * mm] * 5)
lines.setStyle(TableStyle([('LINEBELOW', (0, 0), (-1, -1), 0.4, colors.HexColor('#9a9a9a'))]))
story.append(lines)


def page(c, d):
    c.saveState()
    c.setFont('DV', 7.5)
    c.setFillColor(colors.HexColor('#666666'))
    c.drawString(15 * mm, 9 * mm, f'Nuvio IPTV Prototype · build {a.build}')
    c.drawRightString(195 * mm, 9 * mm, f'Page {d.page}')
    c.restoreState()


SimpleDocTemplate(a.out, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=16 * mm,
                  title=f'Nuvio IPTV test sheet {a.build}').build(story, onFirstPage=page, onLaterPages=page)
print('written', a.out)
