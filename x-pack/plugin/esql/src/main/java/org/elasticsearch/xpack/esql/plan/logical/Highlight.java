/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.xpack.esql.capabilities.PostAnalysisVerificationAware;
import org.elasticsearch.xpack.esql.capabilities.TelemetryAware;
import org.elasticsearch.xpack.esql.common.Failures;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.AttributeSet;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.MapExpression;
import org.elasticsearch.xpack.esql.core.expression.NameId;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.plan.GeneratingPlan;
import org.elasticsearch.xpack.esql.planner.HighlightQueryTranslator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xpack.esql.common.Failure.fail;
import static org.elasticsearch.xpack.esql.expression.NamedExpressions.mergeOutputAttributes;

public class Highlight extends UnaryPlan implements TelemetryAware, GeneratingPlan<Highlight>, PostAnalysisVerificationAware {

    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        LogicalPlan.class,
        "Highlight",
        Highlight::new
    );

    public static final String DEFAULT_PREFIX = "highlight_";

    /** Analyzer used by both verification and execution. */
    public static final Analyzer DEFAULT_ANALYZER = new StandardAnalyzer();

    /**
     * Analyzer applied to {@code keyword}-family ON fields, giving whole-value (non-tokenized) semantics that mirror
     * Query DSL's {@code Lucene.KEYWORD_ANALYZER} fallback for non-tokenized fields. A plain {@link Analyzer}, safe to
     * share as a constant.
     */
    public static final Analyzer KEYWORD_ANALYZER = new KeywordAnalyzer();

    public static final String PRE_TAGS = "pre_tags";
    public static final String POST_TAGS = "post_tags";
    public static final String NUMBER_OF_FRAGMENTS = "number_of_fragments";
    public static final String FRAGMENT_SIZE = "fragment_size";
    public static final String ENCODER = "encoder";
    // Optional user override for the analyzer used to tokenize the query and re-analyze the row text. When set it applies
    // to every ON field, overriding the analyzers derived automatically from the mapping. Only built-in/prebuilt and
    // globally-registered analyzers are resolvable; index-level custom analyzers are not, since HIGHLIGHT runs without a
    // target IndexSettings.
    public static final String ANALYZER = "analyzer";
    public static final String NO_MATCH_SIZE = "no_match_size";
    public static final String BOUNDARY_SCANNER = "boundary_scanner";
    public static final String BOUNDARY_SCANNER_LOCALE = "boundary_scanner_locale";
    public static final String ORDER = "order";
    public static final String MAX_ANALYZED_OFFSET = "max_analyzed_offset";

    // Accepted for Query DSL parity but only used by the FastVectorHighlighter, so they are no-ops for the unified
    // highlighter that HIGHLIGHT always uses.
    public static final String BOUNDARY_CHARS = "boundary_chars";
    public static final String BOUNDARY_MAX_SCAN = "boundary_max_scan";
    public static final String PHRASE_LIMIT = "phrase_limit";

    private static final List<String> VALID_OPTION_NAMES = List.of(
        PRE_TAGS,
        POST_TAGS,
        NUMBER_OF_FRAGMENTS,
        FRAGMENT_SIZE,
        ENCODER,
        ANALYZER,
        BOUNDARY_SCANNER,
        BOUNDARY_SCANNER_LOCALE,
        BOUNDARY_CHARS,
        BOUNDARY_MAX_SCAN,
        ORDER,
        NO_MATCH_SIZE,
        MAX_ANALYZED_OFFSET,
        PHRASE_LIMIT
    );

    private final String prefix;
    private final Expression query;
    private final List<NamedExpression> fields;
    private final MapExpression options;
    /**
     * The generated attributes for the highlighted fields.
     * These are appended to the child's output in the same order as the ON fields,
     * so the operator's appended blocks line up with these layout channels.
     */
    private final List<Attribute> generatedFields;

    public Highlight(
        Source source,
        LogicalPlan child,
        String prefix,
        Expression query,
        List<NamedExpression> fields,
        MapExpression options,
        List<Attribute> generatedFields
    ) {
        super(source, child);
        this.prefix = prefix;
        this.query = query;
        this.fields = fields;
        this.options = options;
        this.generatedFields = generatedFields;
    }

    private Highlight(StreamInput in) throws IOException {
        this(
            Source.readFrom((PlanStreamInput) in),
            in.readNamedWriteable(LogicalPlan.class),
            in.readString(),
            in.readOptionalNamedWriteable(Expression.class),
            in.readNamedWriteableCollectionAsList(NamedExpression.class),
            // MapExpression is registered under the Expression category, not its own, so read it as an Expression.
            (MapExpression) in.readOptionalNamedWriteable(Expression.class),
            in.readNamedWriteableCollectionAsList(Attribute.class)
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        source().writeTo(out);
        out.writeNamedWriteable(child());
        out.writeString(prefix);
        out.writeOptionalNamedWriteable(query);
        out.writeNamedWriteableCollection(fields);
        out.writeOptionalNamedWriteable(options);
        out.writeNamedWriteableCollection(generatedFields);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    public String prefix() {
        return prefix;
    }

    public Expression query() {
        return query;
    }

    public List<NamedExpression> fields() {
        return fields;
    }

    public MapExpression options() {
        return options;
    }

    public static List<String> validOptionNames() {
        return VALID_OPTION_NAMES;
    }

    /**
     * Builds nullable {@code <prefix><field>} keyword attributes for the given highlight fields.
     * A generated name that collides with existing output shadows the earlier column.
     */
    public static List<Attribute> generatedAttributesFor(Source source, String prefix, List<NamedExpression> fields) {
        return fields.stream()
            .map(f -> (Attribute) new ReferenceAttribute(source, null, prefix + f.name(), DataType.KEYWORD, Nullability.TRUE, null, false))
            .toList();
    }

    public Highlight withOptions(MapExpression newOptions) {
        if (Objects.equals(options, newOptions)) {
            return this;
        }
        return new Highlight(source(), child(), prefix, query, fields, newOptions, generatedFields);
    }

    @Override
    public Highlight replaceChild(LogicalPlan newChild) {
        return new Highlight(source(), newChild, prefix, query, fields, options, generatedFields);
    }

    @Override
    protected NodeInfo<? extends LogicalPlan> info() {
        return NodeInfo.create(this, Highlight::new, child(), prefix, query, fields, options, generatedFields);
    }

    @Override
    public List<Attribute> output() {
        return mergeOutputAttributes(generatedFields, child().output());
    }

    @Override
    public List<Attribute> generatedAttributes() {
        return generatedFields;
    }

    @Override
    public Highlight withGeneratedNames(List<String> newNames) {
        checkNumberOfNewNames(newNames);
        List<Attribute> renamed = new ArrayList<>(generatedFields.size());
        for (int i = 0; i < generatedFields.size(); i++) {
            Attribute attr = generatedFields.get(i);
            String newName = newNames.get(i);
            renamed.add(newName.equals(attr.name()) ? attr : attr.withName(newName).withId(new NameId()));
        }
        return new Highlight(source(), child(), prefix, query, fields, options, renamed);
    }

    @Override
    protected AttributeSet computeReferences() {
        // Only the ON fields are inputs; the generated <prefix><field> columns are outputs, not references.
        return Expressions.references(fields);
    }

    @Override
    public boolean expressionsResolved() {
        if (query != null && query.resolved() == false) {
            return false;
        }
        for (NamedExpression field : fields) {
            if (field.resolved() == false) {
                return false;
            }
        }
        return options == null || options.resolved();
    }

    @Override
    public void postAnalysisVerification(Failures failures) {
        verifyFieldTypes(failures);
        if (options == null) {
            return;
        }
        verifyEnum(failures, HighlightOptions.ENCODER_OPTION);
        verifyEnum(failures, HighlightOptions.BOUNDARY_SCANNER_OPTION);
        verifyEnum(failures, HighlightOptions.ORDER_OPTION);
        for (String name : VALID_OPTION_NAMES) {
            verifyValue(failures, name);
        }
    }

    /**
     * Registry-aware verification: on top of the field-type and option checks above, this resolves the optional
     * {@code analyzer} override through the node {@link AnalysisRegistry} (failing fast on an unknown or unloadable name,
     * exactly as {@code LocalExecutionPlanner#resolveHighlightAnalyzer} would at execution) and then verifies the query
     * with the analyzer that will actually be used. The {@link org.elasticsearch.xpack.esql.analysis.Verifier} always
     * calls this overload for plan nodes; {@link #postAnalysisVerification(Failures)} still runs the checks that do not
     * need the registry.
     */
    @Override
    public void postAnalysisVerification(AnalysisRegistry analysisRegistry, Failures failures) {
        postAnalysisVerification(failures);
        Analyzer analyzer = resolveVerificationAnalyzer(analysisRegistry, failures);
        verifyQuery(analyzer, failures);
    }

    /**
     * Resolves the analyzer used to verify the query text, reporting the same failure the execution-time resolution in
     * {@code LocalExecutionPlanner#resolveHighlightAnalyzer} would raise for an unknown or unloadable {@code analyzer}
     * override. This lets a bad analyzer name fail fast during analysis, mirroring the upfront checks for the other
     * options. Only built-in (prebuilt) and globally-registered analyzers resolve here: HIGHLIGHT runs on the
     * coordinator without a target index's {@code IndexSettings}, so index-level custom analyzers are out of scope.
     *
     * @return the analyzer to parse the query with, or {@code null} when the query cannot be checked yet: a non-string
     *         {@code analyzer} value (already reported by {@code verifyValue}), an {@code analyzer} override with no
     *         registry wired (some test contexts — deferred to execution), or an unknown/unloadable name (reported here)
     */
    private Analyzer resolveVerificationAnalyzer(AnalysisRegistry analysisRegistry, Failures failures) {
        Expression value = options == null ? null : foldableOption(ANALYZER);
        if (value == null) {
            // No analyzer override: verify with the shared default, matching LocalExecutionPlanner#planHighlight.
            return DEFAULT_ANALYZER;
        }
        Object folded = value.fold(FoldContext.small());
        // A non-string value is already reported by verifyValue; don't resolve it or double-report.
        if (folded instanceof BytesRef == false && folded instanceof String == false) {
            return null;
        }
        // When the registry isn't wired at verification (e.g. some test contexts), defer to the execution-time check in
        // LocalExecutionPlanner#resolveHighlightAnalyzer rather than risk a NullPointerException here.
        if (analysisRegistry == null) {
            return null;
        }
        String name = BytesRefs.toString(folded);
        try {
            Analyzer analyzer = analysisRegistry.getAnalyzer(name);
            if (analyzer == null) {
                failures.add(fail(this, "'analyzer' must be a built-in or globally-registered analyzer, found [{}]", name));
                return null;
            }
            return analyzer;
        } catch (IOException e) {
            failures.add(fail(this, "failed to load analyzer [{}]: {}", name, e.getMessage()));
            return null;
        }
    }

    /**
     * Builds the query the same way {@code LocalExecutionPlanner#planHighlight} will at execution, so an unsupported
     * function/expression/option or a {@code query_string} syntax error fails fast during analysis. Uses a single
     * {@code analyzer} (the resolved override, or the default) for every field: the per-field mapping analyzers are only
     * resolved later at planning, but they never change which query shapes are legal. Returns early when there is
     * nothing to check yet (no analyzer resolved, or a null/unresolved query).
     */
    private void verifyQuery(Analyzer analyzer, Failures failures) {
        if (analyzer == null || query == null || query.resolved() == false) {
            return;
        }
        List<String> fieldNames = fields.stream().map(NamedExpression::name).toList();
        try {
            HighlightQueryTranslator.translate(query, fieldNames, analyzer);
        } catch (RuntimeException e) {
            failures.add(fail(this, "{}", e.getMessage()));
        }
    }

    private void verifyFieldTypes(Failures failures) {
        for (NamedExpression field : fields) {
            if (field.resolved() && DataType.isString(field.dataType()) == false) {
                failures.add(
                    fail(
                        field,
                        "HIGHLIGHT ON field [{}] must be [text] or [keyword], found [{}]",
                        field.name(),
                        field.dataType().typeName()
                    )
                );
            }
        }
    }

    // Non-foldable values (WITH { ... } allows constants, nested maps and parameters) are skipped here and fail later at
    // fold time.
    private Expression foldableOption(String name) {
        Expression value = options.get(name);
        return value != null && value.foldable() ? value : null;
    }

    private void verifyEnum(Failures failures, HighlightOptions.EnumOption option) {
        Expression value = foldableOption(option.name());
        if (value == null) {
            return;
        }
        String actual = BytesRefs.toString(value.fold(FoldContext.small()));
        if (option.isValid(actual) == false) {
            failures.add(
                fail(this, "Invalid value [{}] for option [{}] in HIGHLIGHT, expected one of {}", actual, option.name(), option.allowed())
            );
        }
    }

    private void verifyValue(Failures failures, String name) {
        Expression value = foldableOption(name);
        if (value == null) {
            return;
        }
        try {
            HighlightOptions.validate(name, value, FoldContext.small());
        } catch (RuntimeException e) {
            failures.add(fail(this, "Invalid value for option [{}] in HIGHLIGHT: {}", name, e.getMessage()));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o) == false) {
            return false;
        }
        Highlight other = (Highlight) o;
        return Objects.equals(prefix, other.prefix)
            && Objects.equals(query, other.query)
            && Objects.equals(fields, other.fields)
            && Objects.equals(options, other.options)
            && Objects.equals(generatedFields, other.generatedFields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), prefix, query, fields, options, generatedFields);
    }
}
