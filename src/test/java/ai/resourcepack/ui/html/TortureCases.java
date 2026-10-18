package ai.resourcepack.ui.html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Pathological pages for {@link Torture}, written as files so a failure can be opened and rerun on its own. */
final class TortureCases {
    private TortureCases() {}

    static void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) Files.delete(p);
        }
        Map<String, byte[]> cases = cases();
        for (var e : cases.entrySet()) Files.write(dir.resolve(e.getKey()), e.getValue());
    }

    private static final String HEAD = "<!doctype html><html><head><meta charset=utf-8><style>body{font:14px sans-serif}</style>";

    static Map<String, byte[]> cases() throws IOException {
        Map<String, byte[]> c = new LinkedHashMap<>();
        // Nesting.
        for (int depth : new int[]{100, 1000, 10_000, 50_000}) {
            c.put("nest-div-" + depth + ".html", nest(depth, "div{border-left:1px solid #f00;padding-left:1px}", "<div>", "</div>"));
            c.put("nest-span-" + depth + ".html", nest(depth, "span{border-left:1px solid #f00;padding:0 1px}", "<span>", "</span>"));
            c.put("nest-flex-" + depth + ".html", nest(depth, ".f{display:flex;padding:1px;gap:1px}", "<div class=f><i>a</i>", "</div>"));
            c.put("nest-grid-" + depth + ".html", nest(depth, ".g{display:grid;grid-template-columns:auto 1fr;padding:1px}", "<div class=g><i>a</i>", "</div>"));
            c.put("nest-table-" + depth + ".html", nest(depth, "td{border:1px solid #00f;padding:1px}", "<table><tr><td>a<td>", "</table>"));
            c.put("nest-list-" + depth + ".html", nest(depth, "", "<ul><li>a", "</ul>"));
            c.put("nest-inlineblock-" + depth + ".html", nest(depth, "b{display:inline-block;padding:1px;border:1px solid}", "<b>", "</b>"));
            c.put("nest-abspos-" + depth + ".html", nest(depth, "div{position:absolute;left:1px;top:1px}", "<div>", "</div>"));
        }
        c.put("nest-opacity-500.html", nest(500, "div{opacity:.99;padding:1px}", "<div>", "</div>"));
        c.put("nest-filter-500.html", nest(500, "div{filter:brightness(1.01);padding:1px}", "<div>", "</div>"));
        c.put("nest-blur-100.html", nest(100, "div{filter:blur(2px);padding:1px}", "<div>", "</div>"));
        c.put("nest-backdrop-100.html", nest(100, "div{backdrop-filter:blur(2px);padding:1px}", "<div>", "</div>"));
        c.put("nest-transform-1000.html", nest(1000, "div{transform:rotate(1deg);padding:1px}", "<div>", "</div>"));
        c.put("nest-fixed-in-transform.html", nest(200, "div{transform:translateX(1px)} p{position:fixed;top:0;left:0}", "<div><p>fixed</p>", "</div>"));
        c.put("nest-zindex-500.html", nest(500, "div{position:relative;z-index:1;padding:1px}", "<div>", "</div>"));
        c.put("nest-overflow-1000.html", nest(1000, "div{overflow:hidden;border-radius:3px;padding:1px}", "<div>", "</div>"));
        c.put("nest-details-1000.html", nest(1000, "", "<details open><summary>s</summary>", "</details>"));
        // Breadth.
        c.put("siblings-div-10k.html", repeat(10_000, "", "<div>x</div>"));
        c.put("siblings-div-100k.html", repeat(100_000, "", "<div>x</div>"));
        c.put("siblings-span-100k.html", repeat(100_000, "", "<span>x </span>"));
        c.put("siblings-flex-10k.html", wrap("<div style=display:flex;flex-wrap:wrap>", repeat(10_000, "", "<b style=flex:1>x</b>"), "</div>"));
        c.put("siblings-grid-10k.html", wrap("<div style=display:grid;grid-template-columns:repeat(auto-fill,20px)>", repeat(10_000, "", "<b>x</b>"), "</div>"));
        c.put("textnodes-100k.html", wrap("<p>", "a<!---->".repeat(100_000).getBytes(StandardCharsets.UTF_8), "</p>"));
        c.put("text-1mb-word.html", page("", "<p>" + "a".repeat(1 << 20) + "</p>"));
        c.put("text-1mb-word-breakall.html", page("p{word-break:break-all}", "<p>" + "a".repeat(1 << 20) + "</p>"));
        c.put("text-1mb-word-anywhere.html", page("p{overflow-wrap:anywhere;width:100px}", "<p>" + "a".repeat(1 << 20) + "</p>"));
        c.put("text-1mb-pre.html", page("", "<pre>" + "a\tb c\n".repeat(1 << 18) + "</pre>"));
        c.put("text-100k-words.html", page("", "<p>" + "word ".repeat(100_000) + "</p>"));
        c.put("text-1mb-nowrap-ellipsis.html", page("p{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;width:200px}", "<p>" + "ab ".repeat(350_000) + "</p>"));
        c.put("text-combining-marks.html", page("", "<p>a" + "\u0301".repeat(100_000) + "</p>"));
        c.put("text-astral.html", page("", "<p>" + "\uD83D\uDE00\uD800x\uDFFF".repeat(20_000) + "</p>"));
        c.put("table-10k-rows.html", page("td{border:1px solid}", "<table>" + "<tr><td>a<td>b<td>c".repeat(10_000) + "</table>"));
        c.put("table-colspan-1e6.html", page("td{border:1px solid}", "<table><tr><td colspan=1000000>a<td>b<tr><td>c</table>"));
        c.put("table-rowspan-1e6.html", page("td{border:1px solid}", "<table><tr><td rowspan=1000000>a<td>b<tr><td>c</table>"));
        c.put("table-colspan-many.html", page("td{border:1px solid}", "<table>" + "<tr><td colspan=1000>a".repeat(1000) + "</table>"));
        c.put("table-1k-columns.html", page("td{border:1px solid}", "<table>" + ("<tr>" + "<td>x".repeat(1000)).repeat(100) + "</table>"));
        // Lengths.
        String[] props = {"width", "height", "margin", "margin-left", "padding", "border-width;border-style:solid", "font-size", "line-height",
                "letter-spacing", "word-spacing", "text-indent", "left;position:relative", "top;position:absolute", "border-radius;border:1px solid",
                "flex-basis;display:flex", "gap;display:flex", "outline-width;outline-style:solid", "min-width", "max-height", "inset;position:fixed",
                "translate", "background-size;background:linear-gradient(red,blue)", "background-position;background:linear-gradient(red,blue)",
                "text-underline-offset;text-decoration:underline", "border-spacing;display:table", "column-gap;display:grid;grid-template-columns:1fr 1fr",
                "tab-size;white-space:pre", "-webkit-line-clamp;display:-webkit-box;-webkit-box-orient:vertical;overflow:hidden"};
        String[][] values = {{"big", "1e9px"}, {"neg", "-1e9px"}, {"inf", "1e400px"}, {"div0", "calc(1px / 0)"}, {"nan", "calc(0px / 0)"},
                {"ovf", "calc(1e308px * 10)"}, {"maxint", "2147483648px"}};
        for (String prop : props) {
            String name = prop.replaceFirst(";.*", "");
            for (String[] v : values) {
                String decl = prop.contains(";") ? name + ":" + v[1] + prop.substring(prop.indexOf(';')) : prop + ":" + v[1];
                c.put("len-" + name + "-" + v[0] + ".html", page(".x{" + decl + "}", "<div class=x>x<span>y</span><p>z</p></div><p>after</p>"));
            }
        }
        c.put("calc-nested-10k.html", page(".x{width:" + "calc(".repeat(10_000) + "1px" + ")".repeat(10_000) + "}", "<div class=x>x</div>"));
        c.put("calc-terms-100k.html", page(".x{width:calc(" + "1px + ".repeat(100_000) + "1px)}", "<div class=x>x</div>"));
        c.put("calc-minmax-nested.html", page(".x{width:" + "min(1px,max(2px,".repeat(2000) + "3px" + "))".repeat(2000) + "}", "<div class=x>x</div>"));
        c.put("number-junk.html", page(".x{width:NaNpx;height:Infinitypx;margin:-0px;padding:+-1px;font-size:1e-400px;line-height:NaN;opacity:NaN;z-index:1e99;flex-grow:1e400;order:99999999999999999999}",
                "<div class=x style=display:flex><b>x</b><b style=flex-grow:1e308>y</b><b style=flex-shrink:1e400;width:1e9px>z</b></div>"));
        c.put("grid-repeat-1e9.html", page(".g{display:grid;grid-template-columns:repeat(1000000000,1px)}", "<div class=g><b>x</b></div>"));
        c.put("grid-repeat-100k.html", page(".g{display:grid;grid-template-columns:repeat(100000,1fr)}", "<div class=g><b>x</b></div>"));
        c.put("grid-span-1e9.html", page(".g{display:grid}.g b{grid-column:span 1000000000;grid-row:span 1000000000}", "<div class=g><b>x</b><b>y</b></div>"));
        c.put("grid-line-1e9.html", page(".g{display:grid}.g b{grid-column:1000000000/-1000000000;grid-row:-99999}", "<div class=g><b>x</b><b>y</b></div>"));
        c.put("grid-areas-huge.html", page(".g{display:grid;grid-template-areas:" + ("'" + "a ".repeat(2000) + "'").repeat(200) + "}.g b{grid-area:a}", "<div class=g><b>x</b></div>"));
        c.put("grid-autoflow-dense-10k.html", page(".g{display:grid;grid-auto-flow:dense;grid-template-columns:repeat(7,1fr)}.g b:nth-child(3n){grid-column:span 5}", "<div class=g>" + "<b>x</b>".repeat(10_000) + "</div>"));
        c.put("list-start-huge.html", page("", "<ol start=2147483647 type=I><li>a<li>b<li>c</ol><ol start=-99999999999 type=i><li>a</ol><ol reversed start=99999999999999><li>a</ol><ol style=list-style:upper-roman start=999999999><li>a</ol>"));
        c.put("columns-1e9.html", page(".x{columns:1000000000}", "<div class=x>" + "word ".repeat(100) + "</div>"));
        c.put("aspect-ratio-junk.html", page("div{width:100px}.a{aspect-ratio:1/0}.b{aspect-ratio:0/0}.c{aspect-ratio:1e-300}.d{aspect-ratio:1e300}",
                "<div class=a>a</div><div class=b>b</div><div class=c>c</div><div class=d>d</div>"));
        // Painting.
        c.put("box-shadow-1000.html", page(".x{width:200px;height:100px;box-shadow:" + join(1000, i -> i + "px " + i + "px " + (i % 50) + "px rgba(0,0,0,.01)") + "}", "<div class=x>x</div>"));
        c.put("box-shadow-1000-inset.html", page(".x{width:200px;height:100px;box-shadow:" + join(1000, i -> "inset 0 0 " + (i % 50) + "px " + i + "px red") + "}", "<div class=x>x</div>"));
        c.put("box-shadow-huge-blur.html", page(".x{width:200px;height:100px;box-shadow:0 0 1e6px 1e6px red}", "<div class=x>x</div>"));
        c.put("box-shadow-huge-spread.html", page(".x{width:200px;height:100px;box-shadow:0 0 100px 1e9px red, inset 0 0 1e9px red}", "<div class=x>x</div>"));
        c.put("text-shadow-1000.html", page("p{text-shadow:" + join(1000, i -> i + "px 0 " + (i % 30) + "px red") + "}", "<p>hello world</p>"));
        c.put("text-shadow-huge-blur.html", page("p{text-shadow:0 0 1e6px red}", "<p>hello world</p>"));
        c.put("filter-blur-huge.html", page(".x{filter:blur(1e6px);width:100px;height:100px;background:red}", "<div class=x></div>"));
        c.put("filter-dropshadow-huge.html", page(".x{filter:drop-shadow(0 0 1e6px red) drop-shadow(1e9px 1e9px 0 blue);width:100px;height:100px;background:red}", "<div class=x></div>"));
        c.put("filter-chain-1000.html", page(".x{filter:" + join(1000, i -> i % 2 == 0 ? "blur(1px)" : "hue-rotate(10deg)").replace(',', ' ') + ";width:100px;height:100px;background:red}", "<div class=x></div>"));
        c.put("backdrop-huge.html", page(".x{backdrop-filter:blur(1e6px);width:100px;height:100px}", "<p>under</p><div class=x></div>"));
        c.put("radius-huge.html", page("div{width:100px;height:50px;border:3px solid;margin:4px}.a{border-radius:1e9px}.b{border-radius:1e400px}.c{border-radius:50%/1e9px}.d{border-radius:1e9px 0 1e9px 0;overflow:hidden}.e{border-radius:-5px}",
                "<div class=a></div><div class=b></div><div class=c></div><div class=d><p>x</p></div><div class=e></div>"));
        c.put("gradient-stops-10k.html", page(".x{height:300px;background:linear-gradient(" + join(10_000, i -> (i % 2 == 0 ? "red " : "blue ") + (i / 100.0) + "%") + ")}", "<div class=x></div>"));
        c.put("gradient-repeat-tiny.html", page(".x{height:300px;background:repeating-linear-gradient(red 0, blue 1e-9px),repeating-radial-gradient(red 0,blue 0)}", "<div class=x></div>"));
        c.put("background-tiny-tile.html", page(".x{height:600px;background:linear-gradient(red,blue) 0 0/1e-6px 1e-6px}", "<div class=x></div>"));
        c.put("background-layers-1000.html", page(".x{height:300px;background:" + join(1000, i -> "linear-gradient(" + i + "deg,rgba(255,0,0,.01),transparent)") + "}", "<div class=x></div>"));
        String[] transforms = {"scale(0)", "scale(1e6)", "scale(1e300)", "scale(-1e9)", "matrix(1e400,0,0,1,0,0)", "matrix(NaN,0,0,1,0,0)", "matrix(0,0,0,0,0,0)",
                "rotate(1e9deg)", "skew(90deg)", "translate(1e9px,1e9px)", "scale(1e-300)", "translate(1e400px)", "skewx(89.9999deg) scale(1e6)"};
        for (int i = 0; i < transforms.length; i++) {
            c.put("transform-" + i + "-" + transforms[i].replaceAll("[^a-z0-9e.-]", "") + ".html",
                    page(".x{transform:" + transforms[i] + ";width:100px;height:60px;background:red;border-radius:9px;box-shadow:0 0 9px blue}.x p{filter:blur(2px)}",
                            "<div class=x><p>text</p></div><p>after</p>"));
        }
        c.put("transform-1e6-filter.html", page(".x{transform:scale(1e6);filter:blur(1px);width:100px;height:60px;background:red}", "<div class=x>t</div>"));
        c.put("transform-1e6-opacity.html", page(".x{transform:scale(1e4);opacity:.5;width:100px;height:60px;background:red}", "<div class=x>t</div>"));
        c.put("fixed-in-transform-scroll.html", page(".t{transform:scale(2)} .f{position:fixed;bottom:0;height:1e9px;background:red}", "<div class=t><div class=f>fixed</div></div>" + "<p>x</p>".repeat(500)));
        StringBuilder z = new StringBuilder();
        Random r = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            z.append("<div style='position:absolute;left:").append(r.nextInt(1000)).append("px;top:").append(r.nextInt(600)).append("px;z-index:")
                    .append(r.nextInt() ).append(";opacity:.").append(r.nextInt(9) + 1).append("'>z");
            if (r.nextInt(4) == 0) z.append("</div>");
        }
        c.put("zindex-chaos-10k.html", page("div{width:20px;height:20px;background:rgba(0,0,255,.3)}", z.toString()));
        // Selectors and sheets.
        c.put("stylesheet-20k-rules.html", page(join(20_000, i -> ".c" + i + " > div:nth-child(" + i + "n+1):not(.d" + i + ") {color:#" + String.format("%06x", i * 997 % 0xffffff) + ";margin:" + (i % 7) + "px}").replace(',', '\n'),
                "<div>" + "<div class=c5><div>x</div></div>".repeat(1000) + "</div>"));
        c.put("selector-descendant-backtrack.html", new String(nest(100, "div " .repeat(60) + "p{color:red}" + " div ".repeat(40) + "span{color:blue}", "<div>", "</div>"), StandardCharsets.UTF_8)
                .replace("x</div>", "<span>x</span>" + "<span>y</span>".repeat(200) + "</div>").getBytes(StandardCharsets.UTF_8));
        c.put("selector-long-100k.html", page(".a" + ".b".repeat(50_000) + " " + "div ".repeat(50_000) + "{color:red}", "<div class=a><div>x</div></div>"));
        c.put("selector-not-nested-5k.html", page("div" + ":not(".repeat(5000) + ".x" + ")".repeat(5000) + "{color:red}", "<div>x</div>"));
        c.put("selector-is-nested-5k.html", page("div" + ":is(".repeat(5000) + "div" + ")".repeat(5000) + "{color:red}", "<div>x</div>"));
        c.put("selector-has-nested.html", page("div" + ":has(div ".repeat(200) + "span" + ")".repeat(200) + "{color:red} :has(*:has(*:has(*:has(*)))){outline:1px solid}",
                new String(nest(300, "", "<div>", "</div>"), StandardCharsets.UTF_8).replaceAll("^.*<body>|</body>.*$", "")));
        c.put("selector-has-wide.html", page("div:has(~ div ~ div ~ div ~ p){color:red} div:has(+ div + div + div){color:blue} *:has(*):nth-last-child(2n+1){outline:1px solid}",
                "<section>" + "<div>x</div>".repeat(3000) + "</section>"));
        c.put("selector-nth-huge.html", page("b:nth-child(99999999999n+1){color:red}b:nth-child(-n+99999999999){color:blue}b:nth-last-of-type(2147483648n-2147483648){color:green}b:nth-child(n+-1){x:y}",
                "<p>" + "<b>x</b>".repeat(5000) + "</p>"));
        c.put("selector-sibling-chain.html", page("b" + " ~ b".repeat(200) + "{color:red}", "<p>" + "<b>x</b>".repeat(2000) + "</p>"));
        c.put("selector-hover-sibling-1k.html", page(join(1000, i -> "b:hover ~ b:nth-child(" + i + ")").replace(',', ' ') + "{color:red}" + join(1000, i -> "#a" + i + ":hover + b{color:blue}").replace(',', '\n'),
                "<p>" + "<b>x</b>".repeat(2000) + "</p>"));
        c.put("var-billion-laughs.html", page(":root{--a:xxxxxxxxxx;" + join(9, i -> "--" + (char) ('b' + i) + ":var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ") var(--" + (char) ('a' + i) + ")").replace(',', ';') + "} p{font-family:var(--j);content:var(--j);width:var(--j)} p::before{content:var(--j)}",
                "<p>x</p>"));
        c.put("var-cycle.html", page(":root{--a:var(--b);--b:var(--a);--c:var(--c,1px);--d:var(--e,var(--f,var(--g,var(--d))))} p{width:var(--a);height:var(--c);margin:var(--d)}", "<p>x</p>"));
        c.put("var-deep-chain.html", page(":root{--v0:1px;" + join(5000, i -> "--v" + (i + 1) + ":calc(var(--v" + i + ") + 1px)").replace(',', ';') + "} p{width:var(--v5000)}", "<p>x</p>"));
        c.put("var-1000-per-element.html", page("p{" + join(1000, i -> "--x" + i + ":" + "y".repeat(100)).replace(',', ';') + "}", "<p>x</p>".repeat(2000)));
        c.put("style-attr-2mb.html", page("", "<p style='" + "color:red;".repeat(200_000) + "'>x</p>"));
        c.put("media-nested-10k.html", page("@media screen{".repeat(10_000) + "p{color:red}" + "}".repeat(10_000), "<p>x</p>"));
        c.put("css-unclosed.html", page("p{color:red;/* never closed " + "{".repeat(10_000) + "\"" + "(".repeat(10_000), "<p>x</p>"));
        c.put("css-braces-100k.html", page("{".repeat(100_000) + "p{color:red}" + "}".repeat(100_000) + "[".repeat(100_000), "<p>x</p>"));
        c.put("pseudo-content-huge.html", page("p::before{content:'" + "x".repeat(500_000) + "'} p::after{content:counter(a) open-quote open-quote open-quote " + "'y' ".repeat(10_000) + "}", "<p>x</p>"));
        c.put("quotes-deep.html", page("q{quotes:'a' 'b'}", "<q>".repeat(5000) + "x"));
        // Images.
        byte[] bigGray = png(30000, 30000, 0, 1);
        byte[] bigRgba = png(30000, 30000, 6, 8);
        byte[] lying = lyingPng(100_000, 100_000);
        byte[] maxSide = png(65536, 1, 0, 1);
        String gray = "data:image/png;base64," + Base64.getEncoder().encodeToString(bigGray);
        String rgba = "data:image/png;base64," + Base64.getEncoder().encodeToString(bigRgba);
        c.put("image-30000-gray.html", page("img{width:300px}", "<img src='" + gray + "'><div style=\"height:200px;background:url('" + gray + "')\"></div>"));
        c.put("image-30000-rgba.html", page("img{width:300px}", "<img src='" + rgba + "'>"));
        c.put("image-30000-declared.html", page("", "<img width=30000 height=30000 src='" + gray + "'><img style=width:30000px;height:30000px src='data:image/png;base64,"
                + Base64.getEncoder().encodeToString(png(2, 2, 6, 8)) + "'>"));
        c.put("image-lying-header.html", page("", "<img src='data:image/png;base64," + Base64.getEncoder().encodeToString(lying) + "'>"));
        c.put("image-65536x1.html", page("", "<img src='data:image/png;base64," + Base64.getEncoder().encodeToString(maxSide) + "'><div style=height:100px;background:url(data:image/png;base64,"
                + Base64.getEncoder().encodeToString(maxSide) + ")></div>"));
        c.put("image-tiny-tiled.html", page(".x{height:576px;background:url(data:image/png;base64," + Base64.getEncoder().encodeToString(png(1, 1, 6, 8)) + ") 0 0/0.001px 0.001px}", "<div class=x></div>"));
        c.put("image-1000-distinct.html", page("img{width:4px}", join(1000, i -> "<img src='data:image/png;base64," + Base64.getEncoder().encodeToString(png(200 + i, 200, 6, 8)) + "'>", "")));
        c.put("image-garbage-data.html", page("", "<img src='data:image/png;base64,!!!!'><img src='data:image/png;base64," + Base64.getEncoder().encodeToString(garbage(5000, 2)) + "'><img src='data:,'><img src='data:'><img src='missing.png'><img src='file:///etc/passwd'><img src='http://127.0.0.1/x.png'>"));
        // SVG.
        StringBuilder laughs = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='300' height='300'><defs><rect id='l0' width='1' height='1' fill='red'/>");
        for (int i = 1; i <= 10; i++) {
            laughs.append("<g id='l").append(i).append("'>");
            for (int j = 0; j < 10; j++) laughs.append("<use href='#l").append(i - 1).append("' xlink:href='#l").append(i - 1).append("' x='").append(j).append("'/>");
            laughs.append("</g>");
        }
        laughs.append("</defs><use href='#l10' xlink:href='#l10'/></svg>");
        c.put("svg-use-laughs-inline.html", page("", laughs.toString()));
        c.put("svg-use-laughs-img.html", page("", "<img src='data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(laughs.toString().getBytes(StandardCharsets.UTF_8)) + "'>"));
        c.put("svg-use-cycle.html", page("", "<svg width=100 height=100><g id=a><use href='#b'/></g><g id=b><use href='#a'/></g><use href='#a'/></svg>"));
        c.put("svg-huge-viewbox.html", page("", "<svg width=1000000000 height=1000000000 viewBox='0 0 1e-300 1e-300'><circle r=1e300 fill=red /></svg><svg viewBox='0 0 1e300 1e300' width=300 height=300><rect width=1e300 height=1e300 /></svg>"));
        c.put("svg-huge-filter.html", page("", "<svg width=600 height=400><filter id=f x='-1e6' y='-1e6' width='2e6' height='2e6'><feGaussianBlur stdDeviation='1e6'/><feMorphology radius='1e9'/></filter><rect width=100 height=100 filter='url(#f)'/></svg>"));
        c.put("svg-nested-1k.html", page("", "<svg width=300 height=300>" + "<g transform='scale(1.01)'>".repeat(1000) + "<rect width=1 height=1/>" + "</g>".repeat(1000) + "</svg>"));
        c.put("svg-pattern-tiny.html", page("", "<svg width=1000 height=500><pattern id=p width=0.001 height=0.001 patternUnits=userSpaceOnUse><rect width=0.0005 height=0.0005 fill=red /></pattern><rect width=1000 height=500 fill='url(#p)'/></svg>"));
        c.put("svg-10k-paths.html", page("", "<svg width=600 height=400>" + "<path d='M0 0 L600 400 L0 400 Z' fill='rgba(255,0,0,.01)'/>".repeat(10_000) + "</svg>"));
        c.put("svg-path-1m.html", page("", "<svg width=600 height=400><path d='M0 0" + " l1 1 l-1 0".repeat(100_000) + "' stroke=red /></svg>"));
        c.put("svg-text-huge-font.html", page("", "<svg width=600 height=400><text font-size='1e9' x=0 y=100>hi</text><text font-size='1e400'>x</text></svg>"));
        // Fonts.
        Files.write(Path.of("build/torture").resolve("garbage.ttf"), garbage(100_000, 3));
        c.put("fonts-fail.html", page("@font-face{font-family:A;src:url(missing.ttf)}@font-face{font-family:B;src:url(data:font/ttf;base64,AAAA)}@font-face{font-family:C;src:url(http://127.0.0.1:1/x.ttf)}"
                + "@font-face{font-family:D;src:url(../garbage.ttf)}@font-face{font-family:E;src:url(data:font/ttf;base64," + Base64.getEncoder().encodeToString(garbage(50_000, 4)) + ")}"
                + "@font-face{font-family:F;src:url(" + "x".repeat(100_000) + ")}" + join(500, i -> "@font-face{font-family:G" + i + ";src:url(missing" + i + ".ttf)}").replace(",", ""),
                "<p style=font-family:A>a</p><p style=font-family:B>b</p><p style=font-family:C>c</p><p style=font-family:D>d</p><p style=font-family:E>e</p><p style=font-family:F,G1,G2>f</p>"));
        c.put("fonts-family-list-10k.html", page("p{font-family:" + join(10_000, i -> "'f" + i + "'") + ",sans-serif}", "<p>hello</p>"));
        c.put("fonts-weights-junk.html", page("p{font-weight:1e9;font-style:oblique 1e9deg;font-size:1e9%;font:1e400px/1e400 x}", "<p>hello</p>"));
        // Files.
        c.put("empty.html", new byte[0]);
        c.put("garbage-1mb.html", garbage(1 << 20, 5));
        c.put("invalid-utf8.html", concat(HEAD.getBytes(StandardCharsets.UTF_8), new byte[]{'<', 'p', '>', (byte) 0xff, (byte) 0xfe, (byte) 0xc3, (byte) 0x28, (byte) 0xe2, (byte) 0x82, 'x', (byte) 0xf0, (byte) 0x90, '<', '/', 'p', '>'}));
        c.put("nul-bytes.html", ("<p>a\0b\0" + "\0".repeat(10_000) + "</p><style>p\0{color:red}</style>").getBytes(StandardCharsets.UTF_8));
        c.put("utf16-bom.html", concat(new byte[]{(byte) 0xff, (byte) 0xfe}, "<p>hello utf16</p>".getBytes(StandardCharsets.UTF_16LE)));
        c.put("lt-1mb.html", "<".repeat(1 << 20).getBytes(StandardCharsets.UTF_8));
        c.put("attrs-100k.html", page("", "<p " + join(100_000, i -> "a" + i + "=" + i).replace(',', ' ') + ">x</p>"));
        c.put("import-self.html", page("@import url(import-self.css);", "<p>x</p>"));
        c.put("import-self.css", "@import url(import-self.css); @import 'import-self.css'; p{color:red}".getBytes(StandardCharsets.UTF_8));
        c.put("link-self.html", (HEAD + "<link rel=stylesheet href=link-self.html><link rel=stylesheet href=/dev/zero><link rel=stylesheet href=/dev/urandom><link rel=stylesheet href=/proc/self/mem></head><body><p>x</p>").getBytes(StandardCharsets.UTF_8));
        c.put("forms-many.html", page("", "<form>" + "<input type=range value=1e400 min=NaN max=-1e9><input type=checkbox checked><select>".repeat(2000) + "<option>x".repeat(10) + "</select><progress value=1e9 max=0></progress><textarea>" + "x\n".repeat(10_000) + "</textarea></form>"));
        return c;
    }

    private static byte[] page(String css, String body) {
        return (HEAD + "<style>" + css + "</style></head><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nest(int depth, String css, String open, String close) {
        return (HEAD + "<style>" + css + "</style></head><body>" + open.repeat(depth) + "x" + close.repeat(depth) + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeat(int n, String css, String unit) {
        return page(css, unit.repeat(n));
    }

    private static byte[] wrap(String open, byte[] inner, String close) {
        String s = new String(inner, StandardCharsets.UTF_8);
        int b = s.indexOf("<body>") + 6, e = s.lastIndexOf("</body>");
        if (b < 6) return (HEAD + "</head><body>" + open + s + close).getBytes(StandardCharsets.UTF_8);
        return (s.substring(0, b) + open + s.substring(b, e) + close + s.substring(e)).getBytes(StandardCharsets.UTF_8);
    }

    private static String join(int n, java.util.function.IntFunction<String> f) {
        return join(n, f, ",");
    }

    private static String join(int n, java.util.function.IntFunction<String> f, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : sep).append(f.apply(i));
        return b.toString();
    }

    private static byte[] garbage(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** A real PNG of [w] x [h] zero pixels ([colourType] 0 grey, 6 RGBA), streamed through the deflater a row at a time. */
    static byte[] png(int w, int h, int colourType, int depth) {
        try {
            return pngOrThrow(w, h, colourType, depth);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] pngOrThrow(int w, int h, int colourType, int depth) throws IOException {
        int channels = colourType == 6 ? 4 : 1;
        int rowBytes = (int) (((long) w * channels * depth + 7) / 8) + 1;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, new Deflater(9), 1 << 16)) {
            byte[] row = new byte[rowBytes];
            for (int y = 0; y < h; y++) z.write(row);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) depth).put((byte) colourType).put((byte) 0).put((byte) 0).put((byte) 0).array());
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** A PNG header stating [w] x [h] RGBA with a few bytes of data. */
    private static byte[] lyingPng(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array());
        chunk(out, "IDAT", new byte[]{0x78, (byte) 0x9c, 0x63, 0, 0, 0, 1, 0, 1});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
