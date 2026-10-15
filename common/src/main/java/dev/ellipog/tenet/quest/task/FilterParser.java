package dev.ellipog.tenet.quest.task;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Legacy filter System expressions, parsed for quest tasks.
 *
 * <p>A filter arrives as the string the author's {@code ftbfiltersystem:smart_filter} stack carries
 * in its {@code ftbfiltersystem:filter} component — {@code or(item(a)item(b))},
 * {@code and(item_tag(minecraft:logs)mod(farmersdelight))} — and the migration tool passes it
 * through verbatim, so Tenet reads the same vocabulary rather than a translation of it. The
 * grammar mirrors {@code FilterParser} on the legacy side: a juxtaposition of {@code TYPE(ARG)}
 * forms, nested to any depth, with the top level acting as an implicit {@code and} and backslash
 * escapes inside arguments.
 *
 * <p>Only the quest-meaningful functions are evaluated: {@code item}, {@code item_tag},
 * {@code mod} and {@code block}, under {@code and}, {@code or} and {@code not}. Any other function
 * ({@code component}, {@code durability} and friends answer questions about one stack that a
 * "hand in N" task cannot ask) is refused here with its name in the message, and the migration
 * tool reports such tasks as manual work rather than emitting them.
 */
public final class FilterParser {

    private FilterParser() {
    }

    /** A parsed filter: what to match, how to say it, and which ids it names. */
    public sealed interface Expr
            permits Expr.And, Expr.Any, Expr.Not, Expr.Item, Expr.Tag, Expr.Mod, Expr.Block {

        /** Whether this stack counts. Missing ids match nothing; the validator warns about them. */
        boolean test(ItemStack stack);

        /** The row's subject: "Oak Log or Any Logs", parenthesised where nesting needs it. */
        String summary();

        /** Every {@code item()} id this names, for the validator's missing-item warning. */
        default List<Identifier> itemIds() {
            return List.of();
        }

        /** Every {@code item_tag()} id this names. */
        default List<Identifier> tagIds() {
            return List.of();
        }

        /** A leaf needs no brackets; a branch does when it sits inside another branch. */
        default boolean atomic() {
            return true;
        }

        /** An {@code and(...)} branch — and the top level's implicit reading. */
        record And(List<Expr> children) implements Expr {
            @Override
            public boolean test(ItemStack stack) {
                for (Expr child : children) {
                    if (!child.test(stack)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public String summary() {
                return join(children, " and ");
            }

            @Override
            public boolean atomic() {
                return false;
            }

            @Override
            public List<Identifier> itemIds() {
                List<Identifier> out = new ArrayList<>();
                for (Expr child : children) {
                    out.addAll(child.itemIds());
                }
                return out;
            }

            @Override
            public List<Identifier> tagIds() {
                List<Identifier> out = new ArrayList<>();
                for (Expr child : children) {
                    out.addAll(child.tagIds());
                }
                return out;
            }

            @Override
            public String toString() {
                StringBuilder out = new StringBuilder("and(");
                for (Expr child : children) {
                    out.append(child.toString());
                }
                return out.append(')').toString();
            }
        }

        /** An {@code or(...)} branch: any member may match. */
        record Any(List<Expr> children) implements Expr {
            @Override
            public boolean test(ItemStack stack) {
                for (Expr child : children) {
                    if (child.test(stack)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String summary() {
                return join(children, " or ");
            }

            @Override
            public boolean atomic() {
                return false;
            }

            @Override
            public List<Identifier> itemIds() {
                List<Identifier> out = new ArrayList<>();
                for (Expr child : children) {
                    out.addAll(child.itemIds());
                }
                return out;
            }

            @Override
            public List<Identifier> tagIds() {
                List<Identifier> out = new ArrayList<>();
                for (Expr child : children) {
                    out.addAll(child.tagIds());
                }
                return out;
            }

            @Override
            public String toString() {
                StringBuilder out = new StringBuilder("or(");
                for (Expr child : children) {
                    out.append(child.toString());
                }
                return out.append(')').toString();
            }
        }

        /** A {@code not(...)} branch of exactly one child. */
        record Not(Expr child) implements Expr {
            @Override
            public boolean test(ItemStack stack) {
                return !child.test(stack);
            }

            @Override
            public String summary() {
                return child.atomic() ? "not " + child.summary() : "not (" + child.summary() + ")";
            }

            @Override
            public boolean atomic() {
                return false;
            }

            @Override
            public List<Identifier> itemIds() {
                return child.itemIds();
            }

            @Override
            public List<Identifier> tagIds() {
                return child.tagIds();
            }

            @Override
            public String toString() {
                return "not(" + child + ")";
            }
        }

        /** An {@code item(...)} leaf: this stack is that item. */
        record Item(Identifier id) implements Expr {
            @Override
            public boolean test(ItemStack stack) {
                net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(id)
                        .map(holder -> holder.value()).orElse(net.minecraft.world.item.Items.AIR);
                return !stack.isEmpty() && stack.is(item);
            }

            @Override
            public String summary() {
                return humanize(id);
            }

            @Override
            public List<Identifier> itemIds() {
                return List.of(id);
            }

            @Override
            public String toString() {
                return "item(" + id + ")";
            }
        }

        /** An {@code item_tag(...)} leaf: this stack is in that tag. */
        record Tag(Identifier id) implements Expr {
            @Override
            public boolean test(ItemStack stack) {
                return !stack.isEmpty()
                        && stack.is(dev.ellipog.tenet.quest.TagKeys.of(
                                net.minecraft.core.registries.Registries.ITEM, id));
            }

            @Override
            public String summary() {
                return dev.ellipog.tenet.quest.TagLabels.humanize(id);
            }

            @Override
            public List<Identifier> tagIds() {
                return List.of(id);
            }

            @Override
            public String toString() {
                return "item_tag(" + id + ")";
            }
        }

        /** A {@code mod(...)} leaf: this stack's item comes from that namespace. */
        record Mod(String namespace) implements Expr {
            @Override
            public boolean test(ItemStack stack) {
                return !stack.isEmpty()
                        && BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace()
                                .equals(namespace);
            }

            @Override
            public String summary() {
                return namespace + " items";
            }

            @Override
            public String toString() {
                return "mod(" + namespace + ")";
            }
        }

        /** A {@code block()} leaf: this stack's item is a placeable block. */
        record Block() implements Expr {
            @Override
            public boolean test(ItemStack stack) {
                return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
            }

            @Override
            public String summary() {
                return "a block";
            }

            @Override
            public String toString() {
                return "block()";
            }
        }

        /** Children joined, bracketing the branches among them. */
        static String join(List<Expr> children, String word) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    out.append(word);
                }
                Expr child = children.get(i);
                if (child.atomic()) {
                    out.append(child.summary());
                }
                else {
                    out.append('(').append(child.summary()).append(')');
                }
            }
            return out.toString();
        }

        /** An id's last path segment in words: {@code oak_log} reads as "Oak Log". */
        static String humanize(Identifier id) {
            String path = id.getPath();
            int slash = path.lastIndexOf('/');
            String name = slash < 0 ? path : path.substring(slash + 1);
            StringBuilder out = new StringBuilder();
            for (String word : name.split("_")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
            return out.isEmpty() ? id.toString() : out.toString();
        }
    }

    /** A filter that cannot be parsed, with the reason in the message. */
    public static final class FilterException extends RuntimeException {
        FilterException(String message) {
            super(message);
        }
    }

    /**
     * Parses an expression, whose top level is an implicit {@code and} — the same reading the legacy
     * own {@code RootFilter} gives {@code item(X)or(...)}.
     *
     * @throws FilterException naming what is wrong and where, for the codec to report
     */
    public static Expr parse(String expression) {
        List<Expr> top = parseList(expression, 0, expression.length());
        if (top.isEmpty()) {
            throw new FilterException("empty filter: name at least one item, tag, mod or block");
        }
        return top.size() == 1 ? top.get(0) : new Expr.And(top);
    }

    /** One {@code TYPE(ARG)} sequence, backslash escapes honoured, parens balanced. */
    static List<Expr> parseList(String text, int from, int to) {
        List<Expr> out = new ArrayList<>();
        int i = from;
        while (true) {
            while (i < to && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= to) {
                return out;
            }
            int open = text.indexOf('(', i);
            if (open < 0 || open >= to) {
                throw new FilterException("missing open parenthesis in: " + text.substring(i, to).strip());
            }
            String type = text.substring(i, open).strip();
            int close = matchingParen(text, open, to);
            String arg = unescape(text.substring(open + 1, close).strip());
            out.add(node(type, arg));
            i = close + 1;
        }
    }

    /** The matching close paren of the open one, honouring backslash escapes. */
    static int matchingParen(String text, int open, int to) {
        String tail = text.substring(open, Math.min(to, text.length()));
        int depth = 0;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '\\') {
                i++;
            }
            else if (c == '(') {
                depth++;
            }
            else if (c == ')') {
                if (--depth == 0) {
                    return open + i;
                }
            }
        }
        throw new FilterException("missing close parenthesis in: " + tail.strip());
    }

    /** Backslash quotes the next character, exactly as the legacy parser reads it. */
    static String unescape(String arg) {
        if (arg.indexOf('\\') < 0) {
            return arg;
        }
        StringBuilder out = new StringBuilder(arg.length());
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                if (++i >= arg.length()) {
                    break;
                }
            }
            out.append(arg.charAt(i));
        }
        return out.toString();
    }

    /** One parsed node: the supported functions, and a refusal naming the rest. */
    static Expr node(String type, String arg) {
        String name = type.contains(":") ? type.substring(type.lastIndexOf(':') + 1) : type;
        return switch (name) {
            case "and" -> {
                List<Expr> children = parseList(arg, 0, arg.length());
                if (children.isEmpty()) {
                    throw new FilterException("and() names no filter");
                }
                yield children.size() == 1 ? children.get(0) : new Expr.And(children);
            }
            case "or" -> {
                List<Expr> children = parseList(arg, 0, arg.length());
                if (children.isEmpty()) {
                    throw new FilterException("or() names no filter");
                }
                yield children.size() == 1 ? children.get(0) : new Expr.Any(children);
            }
            case "not" -> {
                List<Expr> children = parseList(arg, 0, arg.length());
                if (children.size() != 1) {
                    throw new FilterException("not() takes exactly one filter, found "
                            + children.size());
                }
                yield new Expr.Not(children.get(0));
            }
            case "item" -> new Expr.Item(id(arg, "item"));
            case "item_tag" -> new Expr.Tag(id(arg, "item_tag"));
            case "mod" -> {
                if (arg.isEmpty() || arg.contains("(") || arg.contains(")")) {
                    throw new FilterException("mod() takes a mod id, found: " + arg);
                }
                yield new Expr.Mod(arg);
            }
            case "block" -> {
                if (!arg.isEmpty()) {
                    throw new FilterException("block() takes no argument, found: " + arg);
                }
                yield new Expr.Block();
            }
            default ->
                throw new FilterException("unknown filter \"" + type + "\" - quest filters answer "
                        + "item, item_tag, mod and block, under and, or and not");
        };
    }

    /** A namespaced id, defaulting the namespace the way resource locations do. */
    static Identifier id(String arg, String function) {
        if (arg.isEmpty()) {
            throw new FilterException(function + "() takes an id, found nothing");
        }
        Identifier id = Identifier.tryParse(arg);
        if (id == null) {
            throw new FilterException("\"" + arg + "\" is not a valid id in " + function + "()");
        }
        return id;
    }
}
