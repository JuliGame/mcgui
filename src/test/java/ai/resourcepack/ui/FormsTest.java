package ai.resourcepack.ui;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FormsTest {
    private static final String SHOP = """
            <form id="f" data-command="buy {form.item} {form.amount} {form.extras}">
              <input type="radio" name="item" value="bread">
              <input type="radio" name="item" value="cake" checked>
              <select name="amount"><option>1</option><option value="16" selected>Sixteen</option><option>64</option></select>
              <input type="checkbox" name="extras" value="gift" checked>
              <input type="checkbox" name="extras" value="wrap" checked>
              <input type="checkbox" name="extras" value="card">
              <input type="range" name="volume" min="0" max="10">
              <input type="hidden" name="shop" value="baker">
              <button id="go">Buy</button>
              <button id="nothing" type="button">Not a submit</button>
            </form>
            <span id="outside" data-command="say {form.item}"></span>
            """;

    @Test
    void readsEveryKindOfField() {
        Document doc = Jsoup.parse(SHOP);
        Map<String, String> v = Forms.values(doc.getElementById("f"), null);
        assertEquals("cake", v.get("item"));
        assertEquals("16", v.get("amount"));
        assertEquals("gift,wrap", v.get("extras"));
        assertEquals("5", v.get("volume"));
        assertEquals("baker", v.get("shop"));
        assertEquals("buy cake 16 gift,wrap", Forms.fill(doc.getElementById("f").attr("data-command"), v));
    }

    @Test
    void anElementOutsideAFormReadsTheWholePage() {
        Document doc = Jsoup.parse(SHOP);
        Element outside = doc.getElementById("outside");
        assertEquals("say cake", Forms.fill(outside.attr("data-command"), Forms.values(outside, null)));
        assertEquals("say {nothing}", Forms.fill("say {nothing}", Map.of()));
        assertEquals("say ", Forms.fill("say {form.missing}", Map.of()));
    }

    @Test
    void onlyASubmitButtonSendsTheForm() {
        Document doc = Jsoup.parse(SHOP);
        assertSame(doc.getElementById("f"), Forms.submitted(doc.getElementById("go")));
        assertNull(Forms.submitted(doc.getElementById("nothing")));
        assertNull(Forms.submitted(doc.selectFirst("select")));
    }

    @Test
    void aSelectStepsRoundItsOptions() {
        Document doc = Jsoup.parse(SHOP);
        Element select = doc.selectFirst("select");
        Forms.next(select);
        assertEquals("64", Forms.values(select, null).get("amount"));
        Forms.next(select);
        assertEquals("1", Forms.values(select, null).get("amount"));
    }

    @Test
    void aSliderTakesTheValueWhereItWasClicked() {
        Document doc = Jsoup.parse(SHOP);
        Element range = doc.selectFirst("input[type=range]");
        Forms.slide(range, 0.74);
        assertEquals("7", range.attr("value"));
        Forms.slide(range, 2);
        assertEquals("10", range.attr("value"));
        Element number = Jsoup.parse("<input type=number value=3 step=0.5 max=4>").selectFirst("input");
        Forms.slide(number, 0.9);
        assertEquals("3.5", number.attr("value"));
        Forms.slide(number, 0.9);
        Forms.slide(number, 0.9);
        assertEquals("4", number.attr("value"));
        Forms.slide(number, 0.1);
        assertEquals("3.5", number.attr("value"));
    }
}
