package dev.ellipog.tenet.quest.task;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy filter expressions, parsed and matched.
 *
 * <p>T23 of the migration work: tags — and the {@code mod}, {@code and}, {@code or} and
 * {@code not} combinators — reach Tenet through filter stacks carrying this vocabulary, and the
 * migration tool passes it through verbatim. The shapes below are the ones ATM10 actually ships
 * (430 tasks: {@code or} of items, {@code and} of tag and mod, bare {@code item_tag}), plus the
 * refusals for what a "hand in N" task cannot answer.
 */
@DisplayName("filter expressions")
class FilterParserTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    private static FilterParser.Expr parse(String expression) {
        return FilterParser.parse(expression);
    }

    private static void refuses(String expression, String naming) {
        FilterParser.FilterException thrown =
                assertThrows(FilterParser.FilterException.class, () -> parse(expression));
        assertTrue(thrown.getMessage().contains(naming),
                "\"" + expression + "\" was refused without naming " + naming + ": " + thrown.getMessage());
    }

    // ------------------------------------------------------------------
    // Shapes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a single leaf parses to itself, whatever its function")
    void singleLeavesParseToThemselves() {
        assertInstanceOf(FilterParser.Expr.Item.class, parse("item(minecraft:coal)"));
        assertInstanceOf(FilterParser.Expr.Tag.class, parse("item_tag(minecraft:coals)"));
        assertInstanceOf(FilterParser.Expr.Mod.class, parse("mod(minecraft)"));
        assertInstanceOf(FilterParser.Expr.Block.class, parse("block()"));
    }

    @Test
    @DisplayName("a top-level juxtaposition reads as an implicit and, like the legacy format's own root")
    void topLevelJuxtapositionIsAnImplicitAnd() {
        FilterParser.Expr parsed = parse("item(minecraft:coal)or(item(minecraft:iron_ingot)item(minecraft:gold_ingot))");
        assertInstanceOf(FilterParser.Expr.And.class, parsed);
        assertEquals(2, ((FilterParser.Expr.And) parsed).children().size());
    }

    @Test
    @DisplayName("and, or and not nest to any depth")
    void combinatorsNest() {
        FilterParser.Expr parsed =
                parse("or(and(item_tag(minecraft:logs)mod(biomeswevegone))not(mod(minecraft)))");
        assertInstanceOf(FilterParser.Expr.Any.class, parsed);
        FilterParser.Expr.Any or = (FilterParser.Expr.Any) parsed;
        assertEquals(2, or.children().size());
        assertInstanceOf(FilterParser.Expr.And.class, or.children().get(0));
        assertInstanceOf(FilterParser.Expr.Not.class, or.children().get(1));
    }

    @Test
    @DisplayName("backslash escapes survive, and surrounding whitespace is ignored")
    void escapesAndWhitespace() {
        FilterParser.Expr parsed = parse("  or( item(minecraft:coal)  item(minecraft:iron_ingot) ) ");
        assertInstanceOf(FilterParser.Expr.Any.class, parsed);
        assertEquals(2, ((FilterParser.Expr.Any) parsed).children().size());

        assertEquals("a(b", FilterParser.unescape("a\\(b"),
                "an escaped paren is data, not depth");
        assertEquals("a", FilterParser.unescape("a\\"),
                "a trailing backslash is dropped, the way the legacy parser reads it");
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("what a quest cannot answer is refused with its name in the message")
    void refusalsNameTheFunction() {
        refuses("", "empty filter");
        refuses("or()", "or() names no filter");
        refuses("and()", "and() names no filter");
        refuses("not(item(minecraft:coal)item(minecraft:iron_ingot))", "exactly one");
        refuses("component(fuzzy:{\"minecraft:count\":1})", "component");
        refuses("durability(10)", "durability");
        refuses("mystery(minecraft:coal)", "mystery");
        refuses("or(item(minecraft:coal)", "close parenthesis");
        refuses("item(minecraft:coal", "close parenthesis");
        refuses("item()", "takes an id");
        refuses("item(not an id!)", "not a valid id");
        refuses("block(minecraft:coal)", "takes no argument");
    }

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    @DisplayName("printing and re-parsing is stable, for every ATM10 shape")
    void printThenParseIsStable() {
        for (String shape : new String[] {
                "item(minecraft:coal)",
                "item_tag(minecraft:logs)",
                "mod(minecraft)",
                "block()",
                "or(item(minecraft:coal)item(minecraft:iron_ingot))",
                "or(item_tag(minecraft:coals)item_tag(minecraft:logs))",
                "and(item_tag(minecraft:logs)mod(biomeswevegone))",
                "or(and(item_tag(curios:charm)mod(apotheosis)))",
                "and(mod(sushigocrafting)block())",
                "not(mod(minecraft))",
                "item(minecraft:coal)or(item(minecraft:iron_ingot)item(minecraft:gold_ingot))"}) {
            FilterParser.Expr once = parse(shape);
            FilterParser.Expr twice = parse(once.toString());
            assertEquals(once, twice, shape + " did not survive printing as " + once);
        }
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("leaves match what they name and nothing else")
    void leavesMatch() {
        ItemStack coal = new ItemStack(Items.COAL);
        ItemStack stone = new ItemStack(Items.STONE);
        ItemStack stick = new ItemStack(Items.STICK);

        assertTrue(parse("item(minecraft:coal)").test(coal));
        assertFalse(parse("item(minecraft:coal)").test(stone));
        assertFalse(parse("item(minecraft:diamond)").test(coal),
                "a missing id matches nothing rather than everything");

        assertTrue(parse("mod(minecraft)").test(coal));
        assertFalse(parse("mod(othermod)").test(coal));

        assertTrue(parse("block()").test(stone), "stone is a placeable block");
        assertFalse(parse("block()").test(stick), "and a stick is not");
        assertFalse(parse("block()").test(ItemStack.EMPTY), "nor is nothing");
        assertFalse(parse("item(minecraft:coal)").test(ItemStack.EMPTY));
    }

    @Test
    @DisplayName("and, or and not combine the leaves the way their names say")
    void combinatorsCombine() {
        ItemStack coal = new ItemStack(Items.COAL);
        ItemStack stone = new ItemStack(Items.STONE);

        assertTrue(parse("or(item(minecraft:coal)item(minecraft:stone))").test(coal));
        assertTrue(parse("or(item(minecraft:coal)item(minecraft:stone))").test(stone));
        assertFalse(parse("and(item(minecraft:coal)block())").test(coal),
                "coal is not a block, so the conjunction fails it");
        assertTrue(parse("and(item(minecraft:stone)block())").test(stone));
        assertTrue(parse("not(item(minecraft:coal))").test(stone));
        assertFalse(parse("not(item(minecraft:coal))").test(coal));
        assertTrue(parse("item(minecraft:coal)mod(minecraft)").test(coal),
                "the implicit top-level and holds for every member");
        assertFalse(parse("item(minecraft:coal)mod(othermod)").test(coal));
    }

    // ------------------------------------------------------------------
    // Summaries
    // ------------------------------------------------------------------

    @Test
    @DisplayName("summaries read as words, bracketing only where nesting needs it")
    void summariesReadAsWords() {
        assertEquals("Coal", parse("item(minecraft:coal)").summary());
        assertEquals("Coal or Iron Ingot",
                parse("or(item(minecraft:coal)item(minecraft:iron_ingot))").summary());
        assertEquals("Coal and minecraft items",
                parse("and(item(minecraft:coal)mod(minecraft))").summary());
        assertEquals("not Coal", parse("not(item(minecraft:coal))").summary());
        assertEquals("Coal or (Stone and a block)",
                parse("or(item(minecraft:coal)and(item(minecraft:stone)block()))").summary());
    }
}
