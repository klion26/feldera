/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.dbsp.sqlCompiler.compiler.frontend;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.adapter.jdbc.JdbcTableScan;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Uncollect;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalCorrelate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalIntersect;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalMinus;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.logical.LogicalWindow;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlRankFunction;
import org.apache.calcite.sql.SqlUserDefinedTypeNameSpec;
import org.apache.calcite.sql.ddl.SqlAttributeDefinition;
import org.apache.calcite.sql.ddl.SqlCreateTable;
import org.apache.calcite.sql.ddl.SqlCreateType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;
import org.dbsp.sqlCompiler.circuit.DBSPPartialCircuit;
import org.dbsp.sqlCompiler.circuit.operator.DBSPConstantOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDeclaration;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDeindexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDifferentiateOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPFilterOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPFlatMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIndexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIndexedTopKOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIntegrateOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPLagOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPMapIndexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPNegateOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPNoopOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSinkOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSourceMultisetOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPStreamAggregateOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPStreamDistinctOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPStreamJoinOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSubtractOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSumOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPWindowAggregateOperator;
import org.dbsp.sqlCompiler.compiler.CompilerOptions;
import org.dbsp.sqlCompiler.compiler.DBSPCompiler;
import org.dbsp.sqlCompiler.compiler.ICompilerComponent;
import org.dbsp.sqlCompiler.compiler.InputColumnMetadata;
import org.dbsp.sqlCompiler.compiler.InputTableMetadata;
import org.dbsp.sqlCompiler.compiler.ProgramMetadata;
import org.dbsp.sqlCompiler.compiler.errors.InternalCompilerError;
import org.dbsp.sqlCompiler.compiler.errors.UnimplementedException;
import org.dbsp.sqlCompiler.compiler.errors.UnsupportedException;
import org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.CalciteCompiler;
import org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler.RelColumnMetadata;
import org.dbsp.sqlCompiler.compiler.frontend.statements.CreateFunctionStatement;
import org.dbsp.sqlCompiler.compiler.frontend.statements.CreateTableStatement;
import org.dbsp.sqlCompiler.compiler.frontend.statements.CreateTypeStatement;
import org.dbsp.sqlCompiler.compiler.frontend.statements.CreateViewStatement;
import org.dbsp.sqlCompiler.compiler.frontend.statements.DropTableStatement;
import org.dbsp.sqlCompiler.compiler.frontend.statements.FrontEndStatement;
import org.dbsp.sqlCompiler.compiler.frontend.statements.HasSchema;
import org.dbsp.sqlCompiler.compiler.frontend.statements.SqlRemove;
import org.dbsp.sqlCompiler.compiler.frontend.statements.TableModifyStatement;
import org.dbsp.sqlCompiler.ir.DBSPAggregate;
import org.dbsp.sqlCompiler.ir.DBSPFunction;
import org.dbsp.sqlCompiler.ir.DBSPNode;
import org.dbsp.sqlCompiler.ir.expression.DBSPApplyExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPBinaryExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPClosureExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPComparatorExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPConstructorExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPFieldComparatorExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPFieldExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPFlatmap;
import org.dbsp.sqlCompiler.ir.expression.DBSPNoComparatorExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPOpcode;
import org.dbsp.sqlCompiler.ir.expression.DBSPRawTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPSortExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPUnaryExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPVariablePath;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPBoolLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI32Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPZSetLiteral;
import org.dbsp.sqlCompiler.ir.path.DBSPPath;
import org.dbsp.sqlCompiler.ir.path.DBSPSimplePathSegment;
import org.dbsp.sqlCompiler.ir.statement.DBSPFunctionItem;
import org.dbsp.sqlCompiler.ir.statement.DBSPItem;
import org.dbsp.sqlCompiler.ir.statement.DBSPStructItem;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeAny;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeIndexedZSet;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeRawTuple;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeStruct;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeTuple;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeTupleBase;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeUser;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeVec;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeZSet;
import org.dbsp.sqlCompiler.ir.type.IsNumericType;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBool;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeDate;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeInteger;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeTimestamp;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeVoid;
import org.dbsp.util.ICastable;
import org.dbsp.util.IWritesLogs;
import org.dbsp.util.Linq;
import org.dbsp.util.Logger;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.dbsp.sqlCompiler.circuit.operator.DBSPIndexedTopKOperator.TopKNumbering.*;
import static org.dbsp.sqlCompiler.ir.type.DBSPTypeCode.USER;

/**
 * The compiler is stateful: it compiles a sequence of SQL statements
 * defining tables and views.  The views must be defined in terms of
 * the previously defined tables and views.  Multiple views can be
 * compiled.  The result is a circuit which has an input for each table
 * and an output for each view.
 * The function generateOutputForNextView can be used to prevent
 * some views from generating outputs.
 */
public class CalciteToDBSPCompiler extends RelVisitor
        implements IWritesLogs, ICompilerComponent {
    // Result is deposited here
    private DBSPPartialCircuit circuit;
    // Map each compiled RelNode operator to its DBSP implementation.
    final Map<RelNode, DBSPOperator> nodeOperator;
    final TableContents tableContents;
    final CompilerOptions options;
    final DBSPCompiler compiler;
    /** Keep track of position in RelNode tree */
    final List<RelNode> ancestors;
    final ProgramMetadata metadata;

    /**
     * Create a compiler that translated from calcite to DBSP circuits.
     * @param trackTableContents  If true this compiler will track INSERT and DELETE statements.
     * @param options             Options for compilation.
     * @param compiler            Parent compiler; used to report errors.
     */
    public CalciteToDBSPCompiler(boolean trackTableContents,
                                 CompilerOptions options, DBSPCompiler compiler,
                                 ProgramMetadata metadata) {
        this.circuit = new DBSPPartialCircuit(compiler, compiler.metadata);
        this.compiler = compiler;
        this.nodeOperator = new HashMap<>();
        this.tableContents = new TableContents(compiler, trackTableContents);
        this.options = options;
        this.ancestors = new ArrayList<>();
        this.metadata = metadata;
    }

    @Override
    public DBSPCompiler getCompiler() {
        return this.compiler;
    }

    private DBSPType convertType(RelDataType dt, boolean asStruct) {
        return this.compiler.getTypeCompiler().convertType(dt, asStruct);
    }

    private DBSPTypeZSet makeZSet(DBSPType type) {
        return TypeCompiler.makeZSet(type);
    }

    private DBSPTypeIndexedZSet makeIndexedZSet(DBSPType keyType, DBSPType valueType) {
        return TypeCompiler.makeIndexedZSet(keyType, valueType);
    }

    /** Gets the circuit produced so far and starts a new one. */
    public DBSPPartialCircuit getFinalCircuit() {
        DBSPPartialCircuit result = this.circuit;
        this.circuit = new DBSPPartialCircuit(this.compiler, this.compiler.metadata);
        return result;
    }

    /**
     * This retrieves the operator that is an input.  If the operator may
     * produce multiset results and this is not desired (asMultiset = false),
     * a distinct operator is introduced in the circuit.
     */
    private DBSPOperator getInputAs(RelNode input, boolean asMultiset) {
        DBSPOperator op = this.getOperator(input);
        if (op.isMultiset && !asMultiset) {
            op = new DBSPStreamDistinctOperator(CalciteObject.create(input), op);
            this.circuit.addOperator(op);
        }
        return op;
    }

    <T> boolean visitIfMatches(RelNode node, Class<T> clazz, Consumer<T> method) {
        T value = ICastable.as(node, clazz);
        if (value != null) {
            Logger.INSTANCE.belowLevel(this, 4)
                    .append("Processing ")
                    .append(node.toString())
                    .newline();
            method.accept(value);
            return true;
        }
        return false;
    }

    private boolean generateOutputForNextView = true;

    /**
     * @param generate
     * If 'false' the next "create view" statements will not generate
     * an output for the circuit.  This is sticky, it has to be
     * explicitly reset.
     */
    public void generateOutputForNextView(boolean generate) {
         this.generateOutputForNextView = generate;
    }

    /**
     * Helper function for creating aggregates.
     * @param node         RelNode that generates this aggregate.
     * @param aggregates   Aggregates to implement.
     * @param groupCount   Number of groupBy variables.
     * @param inputRowType Type of input row.
     * @param resultType   Type of result produced.
     */
    public DBSPAggregate createAggregate(RelNode node,
            List<AggregateCall> aggregates, DBSPTypeTuple resultType,
            DBSPType inputRowType, int groupCount, ImmutableBitSet groupKeys) {
        CalciteObject obj = CalciteObject.create(node);
        DBSPVariablePath rowVar = inputRowType.ref().var("v");
        DBSPAggregate.Implementation[] implementations = new DBSPAggregate.Implementation[aggregates.size()];
        int aggIndex = 0;

        for (AggregateCall call: aggregates) {
            DBSPType resultFieldType = resultType.getFieldType(aggIndex + groupCount);
            AggregateCompiler compiler = new AggregateCompiler(node,
                    this.getCompiler(), call, resultFieldType, rowVar, groupKeys);
            DBSPAggregate.Implementation implementation = compiler.compile();
            implementations[aggIndex] =  implementation;
            aggIndex++;
        }
        return new DBSPAggregate(obj, rowVar, implementations, false);
    }

    /**
     * Given a struct type find the index of the specified field.
     * Throws if no such field exists.
     */
    static int getFieldIndex(String field, RelDataType type) {
        int index = 0;
        for (RelDataTypeField rowField: type.getFieldList()) {
            if (rowField.getName().equalsIgnoreCase(field))
                return index;
            index++;
        }
        throw new InternalCompilerError("Type " + type + " has no field named " + field, CalciteObject.create(type));
    }

    public void visitCorrelate(LogicalCorrelate correlate) {
        // We decorrelate queries using Calcite's optimizer.
        // So we assume that the only correlated queries we receive
        // are unnest-type queries.  We assume that unnest queries
        // have a restricted plan of this form:
        // LogicalCorrelate(correlation=[$cor0], joinType=[inner], requiredColumns=[{...}])
        //    LeftSubquery
        //    Uncollect
        //      LogicalProject(COL=[$cor0.ARRAY])
        //        LogicalValues(tuples=[[{ 0 }]])
        // Instead of projecting and joining again we directly apply flatmap.
        // The translation for this is:
        // stream.flat_map({
        //   move |x: &Tuple2<Vec<i32>, Option<i32>>, | -> _ {
        //     let xA: Vec<i32> = x.0.clone();
        //     let xB: x.1.clone();
        //     x.0.clone().into_iter().map({
        //        move |e: i32, | -> Tuple3<Vec<i32>, Option<i32>, i32> {
        //            Tuple3::new(xA.clone(), xB.clone(), e)
        //        }
        //     })
        //  });
        CalciteObject node = CalciteObject.create(correlate);
        DBSPTypeTuple type = this.convertType(correlate.getRowType(), false).to(DBSPTypeTuple.class);
        if (correlate.getJoinType().isOuterJoin())
            throw new UnimplementedException(node);
        this.visit(correlate.getLeft(), 0, correlate);
        DBSPOperator left = this.getInputAs(correlate.getLeft(), true);
        DBSPTypeTuple leftElementType = left.getOutputZSetElementType().to(DBSPTypeTuple.class);

        RelNode correlateRight = correlate.getRight();
        if (!(correlateRight instanceof Uncollect))
            throw new UnimplementedException(node);
        Uncollect uncollect = (Uncollect) correlateRight;
        CalciteObject uncollectNode = CalciteObject.create(uncollect);
        RelNode uncollectInput = uncollect.getInput();
        if (!(uncollectInput instanceof LogicalProject))
            throw new UnimplementedException(node);
        LogicalProject project = (LogicalProject) uncollectInput;
        if (project.getProjects().size() != 1)
            throw new UnimplementedException(node);
        RexNode projection = project.getProjects().get(0);
        if (!(projection instanceof RexFieldAccess))
            throw new UnimplementedException(node);
        RexFieldAccess field = (RexFieldAccess) projection;
        RelDataType leftRowType = correlate.getLeft().getRowType();
        // The index of the field that is the array
        int arrayFieldIndex = getFieldIndex(field.getField().getName(), leftRowType);
        List<Integer> allFields = IntStream.range(0, leftElementType.size())
                .boxed()
                .collect(Collectors.toList());
        allFields.add(DBSPFlatmap.ITERATED_ELEMENT);
        DBSPType indexType = null;
        if (uncollect.withOrdinality) {
            // Index field is always last
            indexType = type.getFieldType(type.size() - 1);
            allFields.add(DBSPFlatmap.COLLECTION_INDEX);
        }
        DBSPFlatmap flatmap = new DBSPFlatmap(node, leftElementType, arrayFieldIndex,
                allFields, indexType);
        DBSPFlatMapOperator flatMap = new DBSPFlatMapOperator(uncollectNode,
                flatmap, TypeCompiler.makeZSet(type), left);
        this.assignOperator(correlate, flatMap);
    }

    public void visitUncollect(Uncollect uncollect) {
        // This represents an unnest.
        // flat_map(move |x| { x.0.into_iter().map(move |e| Tuple1::new(e)) })
        CalciteObject node = CalciteObject.create(uncollect);
        DBSPType type = this.convertType(uncollect.getRowType(), false);
        RelNode input = uncollect.getInput();
        DBSPTypeTuple inputRowType = this.convertType(input.getRowType(), false).to(DBSPTypeTuple.class);
        // We expect this to be a single-element tuple whose type is a vector.
        DBSPOperator opInput = this.getInputAs(input, true);
        DBSPType indexType = null;
        List<Integer> indexes = new ArrayList<>();
        indexes.add(DBSPFlatmap.ITERATED_ELEMENT);
        if (uncollect.withOrdinality) {
            DBSPTypeTuple pair = type.to(DBSPTypeTuple.class);
            indexType = pair.getFieldType(1);
            indexes.add(DBSPFlatmap.COLLECTION_INDEX);
        }
        DBSPFlatmap function = new DBSPFlatmap(node, inputRowType, 0,
                indexes, indexType);
        DBSPFlatMapOperator flatMap = new DBSPFlatMapOperator(node, function,
                TypeCompiler.makeZSet(type), opInput);
        this.assignOperator(uncollect, flatMap);
    }

    /** Generate a list of the groupings that have to be evaluated for all aggregates */
    List<ImmutableBitSet> planGroups(
            ImmutableBitSet groupSet,
            ImmutableList<ImmutableBitSet> groupSets) {
        // Decreasing order of cardinality
        List<ImmutableBitSet> ordered = new ArrayList<>(groupSets);
        ordered.sort(Comparator.comparingInt(ImmutableBitSet::cardinality));
        Collections.reverse(ordered);
        return new ArrayList<>(ordered);
    }

    /** Given a list of fields and a tuple t, generate a tuple expression that extracts
     * all these fields from t.
     * @param fields Fields of the variable that appear in the key
     * @param t      Variable whose fields are used to create the key
     * @param slice  Type expected for produced result */
    DBSPTupleExpression generateKeyExpression(ImmutableBitSet fields, DBSPVariablePath t, DBSPTypeTuple slice) {
        DBSPExpression[] keys = new DBSPExpression[fields.cardinality()];
        int next = 0;
        for (int index : fields) {
            keys[next] = t.deepCopy().deref().field(index).applyCloneIfNeeded().cast(slice.getFieldType(index));
            next++;
        }
        return new DBSPTupleExpression(keys);
    }

    /** Implement one aggregate from a set of rollups described by a LogicalAggregate. */
    DBSPOperator implementOneAggregate(LogicalAggregate aggregate, ImmutableBitSet localKeys) {
        CalciteObject node = CalciteObject.create(aggregate);
        RelNode input = aggregate.getInput();
        DBSPOperator opInput = this.getInputAs(input, true);
        List<AggregateCall> aggregateCalls = aggregate.getAggCallList();

        DBSPType type = this.convertType(aggregate.getRowType(), false);
        DBSPTypeTuple tuple = type.to(DBSPTypeTuple.class);
        DBSPType inputRowType = this.convertType(input.getRowType(), false);
        DBSPVariablePath t = inputRowType.ref().var("t");
        DBSPTupleExpression globalKeys = this.generateKeyExpression(
                aggregate.getGroupSet(), t, tuple);
        DBSPType[] aggTypes = Utilities.arraySlice(tuple.tupFields, aggregate.getGroupCount());
        DBSPTypeTuple aggType = new DBSPTypeTuple(aggTypes);
        DBSPAggregate fold = this.createAggregate(aggregate, aggregateCalls, tuple, inputRowType, aggregate.getGroupCount(), localKeys);
        // The aggregate operator will not return a stream of type aggType, but a stream
        // with a type given by fd.defaultZero.
        DBSPTypeTuple typeFromAggregate = fold.defaultZeroType();
        DBSPTypeIndexedZSet aggregateResultType = this.makeIndexedZSet(globalKeys.getType(), typeFromAggregate);

        DBSPTupleExpression localKeyExpression = this.generateKeyExpression(localKeys, t, tuple);
        DBSPClosureExpression makeKeys =
                new DBSPRawTupleExpression(
                        localKeyExpression,
                        DBSPTupleExpression.flatten(t.deref())).closure(t.asParameter());
        DBSPType localGroupType = localKeyExpression.getType();
        DBSPTypeIndexedZSet localGroupAndInput = this.makeIndexedZSet(localGroupType, inputRowType);
        DBSPIndexOperator createIndex = new DBSPIndexOperator(
                node, makeKeys, localGroupAndInput, false, opInput);
        this.circuit.addOperator(createIndex);
        DBSPTypeIndexedZSet aggregateType = this.makeIndexedZSet(localGroupType, typeFromAggregate);

        DBSPOperator agg;
        if (fold.isEmpty()) {
            // No aggregations: just apply distinct
            DBSPVariablePath var = new DBSPVariablePath("t", localGroupAndInput.getKVRefType());
            DBSPExpression addEmpty = new DBSPRawTupleExpression(
                    var.field(0).deref().applyCloneIfNeeded(),
                    new DBSPTupleExpression());
            agg = new DBSPMapIndexOperator(node, addEmpty.closure(var.asParameter()), aggregateType, createIndex);
          this.circuit.addOperator(agg);
            agg = new DBSPStreamDistinctOperator(node, agg);
        } else {
            agg = new DBSPStreamAggregateOperator(
                      node, aggregateType, null, fold, createIndex, fold.isLinear());
        }
        this.circuit.addOperator(agg);

        // Adjust the key such that all local groups are converted to have the same keys as the
        // global group.  This is used as part of the rollup.
        DBSPOperator adjust;
        if (localKeys.equals(aggregate.getGroupSet())) {
            adjust = agg;
        } else {
            // Generate a new key where each field that is in the groupKeys but not in the local is a null.
            DBSPVariablePath reindexVar = new DBSPVariablePath("t", aggregateType.getKVRefType());
            DBSPExpression[] reindexFields = new DBSPExpression[aggregate.getGroupCount()];
            int localIndex = 0;
            for (int globalIndex: aggregate.getGroupSet()) {
                if (localKeys.get(globalIndex)) {
                    reindexFields[globalIndex] = reindexVar
                            .field(0)
                            .deref()
                            .field(localIndex)
                            .applyCloneIfNeeded();
                    localIndex++;
                } else {
                    assert globalIndex < reindexFields.length;
                    reindexFields[globalIndex] = DBSPLiteral.none(globalKeys.fields[globalIndex].getType());
                }
            }
            DBSPExpression remap = new DBSPRawTupleExpression(
                    new DBSPTupleExpression(reindexFields),
                    reindexVar.field(1).deref().applyCloneIfNeeded());
            adjust = new DBSPMapIndexOperator(
                    node, remap.closure(reindexVar.asParameter()), aggregateResultType, agg);
            this.circuit.addOperator(adjust);
        }

        // Flatten the resulting set
        DBSPTypeTupleBase kvType = new DBSPTypeRawTuple(globalKeys.getType().ref(), typeFromAggregate.ref());
        DBSPVariablePath kv = kvType.var("kv");
        DBSPExpression[] flattenFields = new DBSPExpression[aggregate.getGroupCount() + aggType.size()];
        for (int i = 0; i < aggregate.getGroupCount(); i++)
            flattenFields[i] = kv.deepCopy().field(0).deref().field(i).applyCloneIfNeeded().cast(tuple.getFieldType(i));
        for (int i = 0; i < aggType.size(); i++) {
            DBSPExpression flattenField = kv.deepCopy().field(1).deref().field(i).applyCloneIfNeeded();
            // Here we correct from the type produced by the Folder (typeFromAggregate) to the
            // actual expected type aggType (which is the tuple of aggTypes).
            flattenFields[aggregate.getGroupCount() + i] = flattenField.cast(aggTypes[i]);
        }
        DBSPExpression mapper = new DBSPTupleExpression(flattenFields).closure(kv.asParameter());
        DBSPMapOperator map = new DBSPMapOperator(node, mapper, this.makeZSet(tuple), adjust);
        this.circuit.addOperator(map);
        if (aggregate.getGroupCount() != 0 || aggregateCalls.isEmpty()) {
            return map;
        }

        // This almost works, but we have a problem with empty input collections
        // for aggregates without grouping or with empty localKeys.
        // aggregate_stream returns empty collections for empty input collections -- the fold
        // method is never invoked.
        // So we need to do some postprocessing step for this case.
        // The current result is a zset like {}/{c->1}: either the empty set (for an empty input)
        // or the correct count with a weight of 1.
        // We need to produce {z->1}/{c->1}, where z is the actual zero of the fold above.
        // For this we synthesize the following graph:
        // {}/{c->1}------------------------
        //    | map (|x| x -> z}           |
        // {}/{z->1}                       |
        //    | -                          |
        // {} {z->-1}   {z->1} (constant)  |
        //          \  /                  /
        //           +                   /
        //         {z->1}/{}  -----------
        //                 \ /
        //                  +
        //              {z->1}/{c->1}
        DBSPVariablePath _t = tuple.ref().var("_t");
        DBSPExpression toZero = fold.defaultZero().closure(_t.asParameter());
        DBSPOperator map1 = new DBSPMapOperator(node, toZero, this.makeZSet(type), map);
        this.circuit.addOperator(map1);
        DBSPOperator neg = new DBSPNegateOperator(node, map1);
        this.circuit.addOperator(neg);
        DBSPOperator constant = new DBSPConstantOperator(
                node, new DBSPZSetLiteral(fold.defaultZero()), false);
        this.circuit.addOperator(constant);
        DBSPOperator sum = new DBSPSumOperator(node, Linq.list(constant, neg, map));
        this.circuit.addOperator(sum);
        return sum;
    }

    /** Implement a LogicalAggregate.  The LogicalAggregate can contain a rollup,
     * described by a set of groups.  The aggregate is computed for each group,
     * and the results are combined.
     */
    public void visitAggregate(LogicalAggregate aggregate) {
        CalciteObject node = CalciteObject.create(aggregate);
        List<ImmutableBitSet> plan = this.planGroups(
                aggregate.getGroupSet(), aggregate.getGroupSets());

        // Optimize for the case of
        // - no aggregates
        // - no rollup
        // This is just a DISTINCT call - this is how Calcite represents it.
        if (aggregate.getAggCallList().isEmpty() && plan.size() == 1) {
            // No aggregates, this is how DISTINCT is represented.
            RelNode input = aggregate.getInput();
            DBSPOperator opInput = this.getInputAs(input, true);
            DBSPOperator result = new DBSPStreamDistinctOperator(node, opInput);
            this.assignOperator(aggregate, result);
        } else {
            // One aggregate for each group
            List<DBSPOperator> aggregates = Linq.map(plan, b -> this.implementOneAggregate(aggregate, b));
            // The result is the sum of all aggregates
            DBSPOperator sum = new DBSPSumOperator(node, aggregates);
            this.assignOperator(aggregate, sum);
        }
    }

    public void visitScan(TableScan scan, boolean create) {
        CalciteObject node = CalciteObject.create(scan);
        List<String> name = scan.getTable().getQualifiedName();
        String tableName = name.get(name.size() - 1);
        @Nullable
        DBSPOperator source = this.circuit.getInput(tableName);
        // The inputs should have been created while parsing the CREATE TABLE statements.
        if (source != null) {
            // Multiple queries can share an input.
            // Or the input may have been created by a CREATE TABLE statement.
            Utilities.putNew(this.nodeOperator, scan, source);
        } else {
            // Try a view if no table with this name exists.
            source = this.circuit.getOutput(tableName);
            if (source != null) {
                // We add the sink's source because sink operators do not have outputs.
                // A table scan for a sink operator can appear because of
                // a VIEW that is an input to a query.
                Utilities.putNew(this.nodeOperator, scan, source.to(DBSPSinkOperator.class).input());
            } else {
                // Try a noop
                source = this.circuit.getNoop(tableName);
                if (source == null) {
                    if (!create)
                        throw new InternalCompilerError("Could not find operator for table " + tableName, node);

                    // Create external tables table
                    JdbcTableScan jscan = (JdbcTableScan) scan;
                    RelDataType tableRowType = jscan.jdbcTable.getRowType(this.compiler.frontend.typeFactory);
                    DBSPTypeStruct originalRowType = this.convertType(tableRowType, true)
                            .to(DBSPTypeStruct.class)
                            .rename(tableName);
                    DBSPType rowType = originalRowType.toTuple();
                    HasSchema withSchema = new HasSchema(CalciteObject.EMPTY, tableName, false, tableRowType);
                    this.metadata.addTable(withSchema);
                    InputTableMetadata tableMeta = new InputTableMetadata(
                            Linq.map(withSchema.getColumns(), this::convertMetadata));
                    source = new DBSPSourceMultisetOperator(
                            node, CalciteObject.EMPTY,
                            this.makeZSet(rowType), originalRowType,
                            null, tableMeta, tableName);
                    this.circuit.addOperator(source);
                }
                Utilities.putNew(this.nodeOperator, scan, source);
            }
        }
    }

    void assignOperator(RelNode rel, DBSPOperator op) {
        Utilities.putNew(this.nodeOperator, rel, op);
        this.circuit.addOperator(op);
    }

    DBSPOperator getOperator(RelNode node) {
        return Utilities.getExists(this.nodeOperator, node);
    }

    public void visitProject(LogicalProject project) {
        CalciteObject node = CalciteObject.create(project);
        // LogicalProject is not really SQL project, it is rather map.
        RelNode input = project.getInput();
        DBSPOperator opInput = this.getInputAs(input, true);
        DBSPType outputElementType = this.convertType(project.getRowType(), false);
        DBSPTypeTuple tuple = outputElementType.to(DBSPTypeTuple.class);
        DBSPType inputType = this.convertType(project.getInput().getRowType(), false);
        DBSPVariablePath row = inputType.ref().var("t");
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(row, this.compiler);

        List<DBSPExpression> resultColumns = new ArrayList<>();
        int index = 0;
        for (RexNode column : project.getProjects()) {
            if (column instanceof RexOver) {
                throw new UnsupportedException("Optimizer should have removed OVER expressions",
                        CalciteObject.create(column));
            } else {
                DBSPExpression exp = expressionCompiler.compile(column);
                DBSPType expectedType = tuple.getFieldType(index);
                if (!exp.getType().sameType(expectedType)) {
                    // Calcite's optimizations do not preserve types!
                    exp = exp.applyCloneIfNeeded().cast(expectedType);
                }
                resultColumns.add(exp);
                index++;
            }
        }
        DBSPExpression exp = new DBSPTupleExpression(node, resultColumns);
        DBSPExpression mapFunc = new DBSPClosureExpression(node, exp, row.asParameter());
        DBSPMapOperator op = new DBSPMapOperator(
                node, mapFunc, this.makeZSet(outputElementType), opInput);
        // No distinct needed - in SQL project may produce a multiset.
        this.assignOperator(project, op);
    }

    DBSPOperator castOutput(CalciteObject node, DBSPOperator operator, DBSPType outputElementType) {
        DBSPType inputElementType = operator.getOutputZSetElementType();
        if (inputElementType.sameType(outputElementType))
            return operator;
        DBSPExpression function = inputElementType.caster(outputElementType);
        DBSPOperator map = new DBSPMapOperator(
                node, function, this.makeZSet(outputElementType), operator);
        this.circuit.addOperator(map);
        return map;
    }

    private void visitUnion(LogicalUnion union) {
        CalciteObject node = CalciteObject.create(union);
        RelDataType rowType = union.getRowType();
        DBSPType outputType = this.convertType(rowType, false);
        List<DBSPOperator> inputs = Linq.map(union.getInputs(), this::getOperator);
        // input type nullability may not match
        inputs = Linq.map(inputs, o -> this.castOutput(node, o, outputType));
        DBSPSumOperator sum = new DBSPSumOperator(node, inputs);
        if (union.all) {
            this.assignOperator(union, sum);
        } else {
            this.circuit.addOperator(sum);
            DBSPStreamDistinctOperator d = new DBSPStreamDistinctOperator(node, sum);
            this.assignOperator(union, d);
        }
    }

    private void visitMinus(LogicalMinus minus) {
        CalciteObject node = CalciteObject.create(minus);
        boolean first = true;
        RelDataType rowType = minus.getRowType();
        DBSPType outputType = this.convertType(rowType, false);
        List<DBSPOperator> inputs = new ArrayList<>();
        for (RelNode input : minus.getInputs()) {
            DBSPOperator opInput = this.getInputAs(input, false);
            if (!first) {
                DBSPOperator neg = new DBSPNegateOperator(node, opInput);
                neg = this.castOutput(node, neg, outputType);
                this.circuit.addOperator(neg);
                inputs.add(neg);
            } else {
                opInput = this.castOutput(node, opInput, outputType);
                inputs.add(opInput);
            }
            first = false;
        }

        DBSPSumOperator sum = new DBSPSumOperator(node, inputs);
        if (minus.all) {
            this.assignOperator(minus, sum);
        } else {
            this.circuit.addOperator(sum);
            DBSPStreamDistinctOperator d = new DBSPStreamDistinctOperator(node, sum);
            this.assignOperator(minus, d);
        }
    }

    public void visitFilter(LogicalFilter filter) {
        // If this filter is already implemented, use it.
        // This comes from the implementation of Filter(Window)
        // that compiles to nested TopK, which is detected in Window.
        if (this.filterImplementation != null) {
            Utilities.putNew(this.nodeOperator, filter, this.filterImplementation);
            this.filterImplementation = null;
            return;
        }

        CalciteObject node = CalciteObject.create(filter);
        DBSPType type = this.convertType(filter.getRowType(), false);
        DBSPVariablePath t = type.ref().var("t");
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(t, this.compiler);
        DBSPExpression condition = expressionCompiler.compile(filter.getCondition());
        condition = ExpressionCompiler.wrapBoolIfNeeded(condition);
        condition = new DBSPClosureExpression(CalciteObject.create(filter.getCondition()), condition, t.asParameter());
        DBSPOperator input = this.getOperator(filter.getInput());
        DBSPFilterOperator fop = new DBSPFilterOperator(node, condition, input);
        this.assignOperator(filter, fop);
    }

    private DBSPOperator filterNonNullKeys(LogicalJoin join,
            List<Integer> keyFields, DBSPOperator input) {
        CalciteObject node = CalciteObject.create(join);
        DBSPTypeTuple rowType = input.getType().to(DBSPTypeZSet.class).elementType.to(DBSPTypeTuple.class);
        boolean shouldFilter = Linq.any(keyFields, i -> rowType.tupFields[i].mayBeNull);
        if (!shouldFilter) return input;

        DBSPVariablePath var = rowType.ref().var("r");
        // Build a condition that checks whether any of the key fields is null.
        @Nullable
        DBSPExpression condition = null;
        for (int i = 0; i < rowType.size(); i++) {
            if (keyFields.contains(i)) {
                DBSPFieldExpression field = var.deref().field(i);
                DBSPExpression expr = field.is_null();
                if (condition == null)
                    condition = expr;
                else
                    condition = new DBSPBinaryExpression(
                            node, new DBSPTypeBool(CalciteObject.EMPTY, false), DBSPOpcode.OR, condition, expr);
            }
        }

        Objects.requireNonNull(condition);
        condition = new DBSPUnaryExpression(node, condition.getType(), DBSPOpcode.NOT, condition);
        DBSPClosureExpression filterFunc = condition.closure(var.asParameter());
        DBSPFilterOperator filter = new DBSPFilterOperator(node, filterFunc, input);
        this.circuit.addOperator(filter);
        return filter;
    }

    private void visitJoin(LogicalJoin join) {
        CalciteObject node = CalciteObject.create(join);
        JoinRelType joinType = join.getJoinType();
        if (joinType == JoinRelType.ANTI || joinType == JoinRelType.SEMI)
            throw new UnimplementedException(node);

        DBSPTypeTuple resultType = this.convertType(join.getRowType(), false).to(DBSPTypeTuple.class);
        if (join.getInputs().size() != 2)
            throw new InternalCompilerError("Unexpected join with " + join.getInputs().size() + " inputs", node);
        DBSPOperator left = this.getInputAs(join.getInput(0), true);
        DBSPOperator right = this.getInputAs(join.getInput(1), true);
        DBSPTypeTuple leftElementType = left.getType().to(DBSPTypeZSet.class).elementType
                .to(DBSPTypeTuple.class);

        JoinConditionAnalyzer analyzer = new JoinConditionAnalyzer(node,
                leftElementType.to(DBSPTypeTuple.class).size(), this.compiler.getTypeCompiler());
        JoinConditionAnalyzer.ConditionDecomposition decomposition = analyzer.analyze(join.getCondition());
        // If any key field is nullable we need to filter the inputs; this will make key columns non-nullable
        DBSPOperator filteredLeft = this.filterNonNullKeys(join, Linq.map(decomposition.comparisons, c -> c.leftColumn), left);
        DBSPOperator filteredRight = this.filterNonNullKeys(join, Linq.map(decomposition.comparisons, c -> c.rightColumn), right);

        leftElementType = filteredLeft.getType().to(DBSPTypeZSet.class).elementType.to(DBSPTypeTuple.class);
        DBSPTypeTuple rightElementType = filteredRight.getType().to(DBSPTypeZSet.class).elementType
                .to(DBSPTypeTuple.class);

        int leftColumns = leftElementType.size();
        int rightColumns = rightElementType.size();
        int totalColumns = leftColumns + rightColumns;
        DBSPTypeTuple leftResultType = resultType.slice(0, leftColumns);
        DBSPTypeTuple rightResultType = resultType.slice(leftColumns, leftColumns + rightColumns);

        DBSPVariablePath l = leftElementType.ref().var("l");
        DBSPVariablePath r = rightElementType.ref().var("r");
        DBSPTupleExpression lr = DBSPTupleExpression.flatten(l.deref(), r.deref());
        List<DBSPExpression> leftKeyFields = Linq.map(
                decomposition.comparisons,
                c -> l.deepCopy().deref().field(c.leftColumn).applyCloneIfNeeded().cast(c.commonType));
        List<DBSPExpression> rightKeyFields = Linq.map(
                decomposition.comparisons,
                c -> r.deepCopy().deref().field(c.rightColumn).applyCloneIfNeeded().cast(c.commonType));
        DBSPExpression leftKey = new DBSPTupleExpression(node, leftKeyFields);
        DBSPExpression rightKey = new DBSPTupleExpression(node, rightKeyFields);

        @Nullable
        RexNode leftOver = decomposition.getLeftOver();
        DBSPExpression condition = null;
        DBSPExpression originalCondition = null;
        if (leftOver != null) {
            DBSPVariablePath t = lr.getType().ref().var("t");
            ExpressionCompiler expressionCompiler = new ExpressionCompiler(t, this.compiler);
            condition = expressionCompiler.compile(leftOver);
            if (condition.getType().mayBeNull)
                condition = ExpressionCompiler.wrapBoolIfNeeded(condition);
            originalCondition = condition;
            condition = new DBSPClosureExpression(CalciteObject.create(join.getCondition()), condition, t.asParameter());
        }
        DBSPVariablePath k = leftKey.getType().ref().var("k");

        DBSPTupleExpression tuple = DBSPTupleExpression.flatten(l.deref());
        DBSPClosureExpression toLeftKey = new DBSPRawTupleExpression(leftKey, tuple)
                .closure(l.asParameter());
        DBSPIndexOperator leftIndex = new DBSPIndexOperator(
                node, toLeftKey,
                this.makeIndexedZSet(leftKey.getType(), leftElementType), false, filteredLeft);
        this.circuit.addOperator(leftIndex);

        DBSPClosureExpression toRightKey = new DBSPRawTupleExpression(
                rightKey, DBSPTupleExpression.flatten(r.deref()))
                .closure(r.asParameter());
        DBSPIndexOperator rIndex = new DBSPIndexOperator(
                node, toRightKey,
                this.makeIndexedZSet(rightKey.getType(), rightElementType), false, filteredRight);
        this.circuit.addOperator(rIndex);

        DBSPClosureExpression makeTuple = lr.closure(k.asParameter(), l.asParameter(), r.asParameter());
        DBSPOperator joinResult = new DBSPStreamJoinOperator(node, this.makeZSet(lr.getType()),
                makeTuple, left.isMultiset || right.isMultiset, leftIndex, rIndex);

        // Save the result of the inner join here
        DBSPOperator inner = joinResult;
        if (originalCondition != null) {
            // Apply additional filters
            DBSPBoolLiteral bLit = originalCondition.as(DBSPBoolLiteral.class);
            if (bLit == null || bLit.value == null || !bLit.value) {
                // Technically if blit.value == null or !blit.value then
                // the filter is false, and the result is empty.  But hopefully
                // the calcite optimizer won't allow that.
                this.circuit.addOperator(joinResult);
                inner = new DBSPFilterOperator(node, condition, joinResult);
                joinResult = inner;
            }
            // if bLit it true we don't need to filter.
        }

        if (!resultType.sameType(lr.getType())) {
            // For outer joins additional columns may become nullable.
            DBSPVariablePath t = new DBSPVariablePath("t", lr.getType().ref());
            DBSPExpression[] casts = new DBSPExpression[lr.size()];
            for (int index = 0; index < lr.size(); index++) {
                casts[index] = t.deref().field(index).applyCloneIfNeeded().cast(resultType.getFieldType(index));
            }
            DBSPTupleExpression allFields = new DBSPTupleExpression(casts);
            this.circuit.addOperator(joinResult);
            joinResult = new DBSPMapOperator(node, allFields.closure(t.asParameter()),
                    this.makeZSet(resultType), joinResult);
        }

        // Handle outer joins
        DBSPOperator result = joinResult;
        DBSPVariablePath joinVar = lr.getType().ref().var("j");
        if (joinType == JoinRelType.LEFT || joinType == JoinRelType.FULL) {
            this.circuit.addOperator(result);
            // project the join on the left columns
            DBSPClosureExpression toLeftColumns =
                    DBSPTupleExpression.flatten(joinVar.deref())
                            .slice(0, leftColumns)
                            .pointwiseCast(leftResultType).closure(joinVar.asParameter());
            DBSPOperator joinLeftColumns = new DBSPMapOperator(
                    node, toLeftColumns, this.makeZSet(leftResultType), inner);
            this.circuit.addOperator(joinLeftColumns);
            DBSPOperator distJoin = new DBSPStreamDistinctOperator(node, joinLeftColumns);
            this.circuit.addOperator(distJoin);

            // subtract from left relation
            DBSPOperator leftCast = left;
            if (!leftResultType.sameType(leftElementType)) {
                DBSPClosureExpression castLeft =
                    DBSPTupleExpression.flatten(l.deref())
                            .pointwiseCast(leftResultType).closure(l.asParameter()
                );
                leftCast = new DBSPMapOperator(node, castLeft, this.makeZSet(leftResultType), left);
                this.circuit.addOperator(leftCast);
            }
            DBSPOperator sub = new DBSPSubtractOperator(node, leftCast, distJoin);
            this.circuit.addOperator(sub);
            DBSPStreamDistinctOperator dist = new DBSPStreamDistinctOperator(node, sub);
            this.circuit.addOperator(dist);

            // fill nulls in the right relation fields
            DBSPTupleExpression rEmpty = new DBSPTupleExpression(
                    Linq.map(rightElementType.tupFields,
                             et -> DBSPLiteral.none(et.setMayBeNull(true)), DBSPExpression.class));
            DBSPVariablePath lCasted = leftResultType.ref().var("l");
            DBSPClosureExpression leftRow = DBSPTupleExpression.flatten(lCasted.deref(), rEmpty).closure(
                    lCasted.asParameter());
            DBSPOperator expand = new DBSPMapOperator(node, leftRow, this.makeZSet(resultType), dist);
            this.circuit.addOperator(expand);
            result = new DBSPSumOperator(node, result, expand);
        }
        if (joinType == JoinRelType.RIGHT || joinType == JoinRelType.FULL) {
            this.circuit.addOperator(result);

            // project the join on the right columns
            DBSPClosureExpression toRightColumns =
                    DBSPTupleExpression.flatten(joinVar.deref())
                            .slice(leftColumns, totalColumns)
                            .pointwiseCast(rightResultType).closure(
                    joinVar.asParameter());
            DBSPOperator joinRightColumns = new DBSPMapOperator(
                    node, toRightColumns, this.makeZSet(rightResultType), inner);
            this.circuit.addOperator(joinRightColumns);
            DBSPOperator distJoin = new DBSPStreamDistinctOperator(node, joinRightColumns);
            this.circuit.addOperator(distJoin);

            // subtract from right relation
            DBSPOperator rightCast = right;
            if (!rightResultType.sameType(rightElementType)) {
                DBSPClosureExpression castRight =
                        DBSPTupleExpression.flatten(r.deref())
                                .pointwiseCast(rightResultType).closure(
                        r.asParameter());
                rightCast = new DBSPMapOperator(node, castRight, this.makeZSet(rightResultType), right);
                this.circuit.addOperator(rightCast);
            }
            DBSPOperator sub = new DBSPSubtractOperator(node, rightCast, distJoin);
            this.circuit.addOperator(sub);
            DBSPStreamDistinctOperator dist = new DBSPStreamDistinctOperator(node, sub);
            this.circuit.addOperator(dist);

            // fill nulls in the left relation fields
            DBSPTupleExpression lEmpty = new DBSPTupleExpression(
                    Linq.map(leftElementType.tupFields,
                            et -> DBSPLiteral.none(et.setMayBeNull(true)), DBSPExpression.class));
            DBSPVariablePath rCasted = rightResultType.ref().var("r");
            DBSPClosureExpression rightRow =
                    DBSPTupleExpression.flatten(lEmpty, rCasted.deref()).closure(
                    rCasted.asParameter());
            DBSPOperator expand = new DBSPMapOperator(node,
                    rightRow, this.makeZSet(resultType), dist);
            this.circuit.addOperator(expand);
            result = new DBSPSumOperator(node, result, expand);
        }

        this.assignOperator(join, Objects.requireNonNull(result));
    }

    @Nullable
    ModifyTableTranslation modifyTableTranslation;

    /**
     * Visit a LogicalValue: a SQL literal, as produced by a VALUES expression.
     * This can be invoked by a DDM statement, or by a SQL query that computes a constant result.
     */
    public void visitLogicalValues(LogicalValues values) {
        CalciteObject node = CalciteObject.create(values);
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(null, this.compiler);
        DBSPTypeTuple sourceType = this.convertType(values.getRowType(), false).to(DBSPTypeTuple.class);
        DBSPTypeTuple resultType;
        if (this.modifyTableTranslation != null) {
            resultType = this.modifyTableTranslation.getResultType();
            if (sourceType.size() != resultType.size())
                throw new InternalCompilerError("Expected a tuple with " + resultType.size() +
                        " values but got " + values, node);
        } else {
            resultType = sourceType;
        }

        DBSPZSetLiteral result = DBSPZSetLiteral.emptyWithElementType(resultType);
        for (List<RexLiteral> t : values.getTuples()) {
            List<DBSPExpression> expressions = new ArrayList<>();
            if (t.size() != sourceType.size())
                throw new InternalCompilerError("Expected a tuple with " + sourceType.size() +
                        " values but got " + t, node);
            int i = 0;
            for (RexLiteral rl : t) {
                DBSPType resultFieldType = resultType.tupFields[i];
                DBSPExpression expr = expressionCompiler.compile(rl);
                if (expr.is(DBSPLiteral.class)) {
                    // The expression compiler does not actually have type information
                    // so the nulls produced will have the wrong type.
                    DBSPLiteral lit = expr.to(DBSPLiteral.class);
                    if (lit.isNull)
                        expr = DBSPLiteral.none(resultFieldType);
                }
                if (!expr.getType().sameType(resultFieldType)) {
                    DBSPExpression cast = expr.cast(resultFieldType);
                    expressions.add(cast);
                } else {
                    expressions.add(expr);
                }
                i++;
            }
            DBSPTupleExpression expression = new DBSPTupleExpression(node, expressions);
            result.add(expression);
        }

        if (this.modifyTableTranslation != null) {
            this.modifyTableTranslation.setResult(result);
        } else {
            DBSPOperator constant = new DBSPConstantOperator(node, result, false);
            this.assignOperator(values, constant);
        }
    }

    public void visitIntersect(LogicalIntersect intersect) {
        CalciteObject node = CalciteObject.create(intersect);
        // Intersect is a special case of join.
        List<RelNode> inputs = intersect.getInputs();
        RelNode input = intersect.getInput(0);
        DBSPOperator previous = this.getInputAs(input, false);

        if (inputs.isEmpty())
            throw new UnsupportedException(node);
        if (inputs.size() == 1) {
            Utilities.putNew(this.nodeOperator, intersect, previous);
            return;
        }

        DBSPType inputRowType = this.convertType(input.getRowType(), false);
        DBSPTypeTuple resultType = this.convertType(intersect.getRowType(), false).to(DBSPTypeTuple.class);
        DBSPVariablePath t = inputRowType.ref().var("t");
        DBSPExpression entireKey =
                new DBSPRawTupleExpression(
                        t.deref().applyClone(),
                        new DBSPRawTupleExpression()).closure(
                t.asParameter());
        DBSPVariablePath l = new DBSPTypeRawTuple().ref().var("l");
        DBSPVariablePath r = new DBSPTypeRawTuple().ref().var("r");
        DBSPVariablePath k = inputRowType.ref().var("k");

        DBSPClosureExpression closure = k.deref().applyClone().closure(
                k.asParameter(), l.asParameter(), r.asParameter());
        for (int i = 1; i < inputs.size(); i++) {
            DBSPOperator previousIndex = new DBSPIndexOperator(
                    node, entireKey,
                    this.makeIndexedZSet(inputRowType, new DBSPTypeRawTuple()),
                    previous.isMultiset, previous);
            this.circuit.addOperator(previousIndex);
            DBSPOperator inputI = this.getInputAs(intersect.getInput(i), false);
            DBSPOperator index = new DBSPIndexOperator(
                    node, entireKey.deepCopy(),
                    this.makeIndexedZSet(inputRowType, new DBSPTypeRawTuple()),
                    inputI.isMultiset, inputI);
            this.circuit.addOperator(index);
            previous = new DBSPStreamJoinOperator(node, this.makeZSet(resultType),
                    closure, false, previousIndex, index);
            this.circuit.addOperator(previous);
        }
        Utilities.putNew(this.nodeOperator, intersect, previous);
    }

    /** If this is not null, the parent LogicalFilter should use this implementation
     * instead of generating code. */
    @Nullable DBSPOperator filterImplementation = null;

    /** Index the data according to the keys specified by a window operator */
    DBSPOperator indexWindow(LogicalWindow window, Window.Group group) {
        CalciteObject node = CalciteObject.create(window);
        // This code duplicates code from the SortNode.
        RelNode input = window.getInput();
        DBSPOperator opInput = this.getOperator(input);
        DBSPType inputRowType = this.convertType(input.getRowType(), false);
        DBSPVariablePath t = inputRowType.ref().var("t");
        DBSPExpression[] fields = new DBSPExpression[group.keys.cardinality()];
        int ix = 0;
        for (int field : group.keys.toList()) {
            fields[ix++] = t.deref().field(field);
        }
        DBSPExpression groupKeys =
                new DBSPRawTupleExpression(
                        new DBSPTupleExpression(fields),
                        DBSPTupleExpression.flatten(t.deref())).closure(t.asParameter());
        DBSPOperator index = new DBSPIndexOperator(
                node, groupKeys,
                this.makeIndexedZSet(groupKeys.getType(), inputRowType),
                opInput.isMultiset, opInput);
        this.circuit.addOperator(index);
        return index;
    }

    DBSPComparatorExpression generateComparator(Window.Group group, DBSPComparatorExpression comparator) {
        for (RelFieldCollation collation : group.orderKeys.getFieldCollations()) {
            int field = collation.getFieldIndex();
            RelFieldCollation.Direction direction = collation.getDirection();
            boolean ascending = switch (direction) {
                case ASCENDING -> true;
                case DESCENDING -> false;
                default -> throw new UnimplementedException(comparator.getNode());
            };
            comparator = new DBSPFieldComparatorExpression(comparator.getNode(), comparator, field, ascending);
        }
        return comparator;
    }

    void generateNestedTopK(LogicalWindow window, Window.Group group, int limit, SqlKind kind) {
        CalciteObject node = CalciteObject.create(window);
        DBSPIndexedTopKOperator.TopKNumbering numbering = switch (kind) {
            case RANK -> RANK;
            case DENSE_RANK -> DENSE_RANK;
            case ROW_NUMBER -> ROW_NUMBER;
            default -> throw new UnimplementedException("Ranking function " + kind + " not yet implemented", node);
        };

        DBSPOperator index = this.indexWindow(window, group);

        // Generate comparison function for sorting the vector
        RelNode input = window.getInput();
        DBSPType inputRowType = this.convertType(input.getRowType(), false);
        DBSPComparatorExpression comparator = new DBSPNoComparatorExpression(node, inputRowType);
        comparator = this.generateComparator(group, comparator);

        // The rank must be added at the end of the input tuple (that's how Calcite expects it).
        DBSPVariablePath left = new DBSPVariablePath("left", new DBSPTypeInteger(
                node, 64, true, false));
        DBSPVariablePath right = new DBSPVariablePath("right", inputRowType.ref());
        List<DBSPExpression> flattened = DBSPTypeTupleBase.flatten(right.deref());
        flattened.add(left);
        DBSPTupleExpression tuple = new DBSPTupleExpression(flattened, false);
        DBSPClosureExpression outputProducer = tuple.closure(left.asParameter(), right.asParameter());

        // TopK operator.
        // Since TopK is always incremental we have to wrap it into a D-I pair
        DBSPDifferentiateOperator diff = new DBSPDifferentiateOperator(node, index);
        this.circuit.addOperator(diff);
        DBSPI32Literal limitValue = new DBSPI32Literal(limit);
        DBSPIndexedTopKOperator topK = new DBSPIndexedTopKOperator(
                node, numbering, comparator, limitValue, outputProducer, diff);
        this.circuit.addOperator(topK);
        DBSPIntegrateOperator integral = new DBSPIntegrateOperator(node, topK);
        this.circuit.addOperator(integral);
        // We must drop the index we built.
        DBSPDeindexOperator deindex = new DBSPDeindexOperator(node, integral);
        if (this.filterImplementation != null)
            throw new InternalCompilerError("Unexpected filter implementation ", node);
        this.filterImplementation = deindex;
        this.assignOperator(window, deindex);
    }

    DBSPOperator generateLeadLag(
            LogicalWindow window, Window.Group group,
            AggregateCall aggCall, DBSPType inputRowType, DBSPOperator input) {
        CalciteObject node = CalciteObject.create(window);
        SqlAggFunction aggFunction = aggCall.getAggregation();
        int offset = aggFunction.kind == SqlKind.LAG ? -1 : +1;

        List<Integer> operands = aggCall.getArgList();

        DBSPVariablePath var = new DBSPVariablePath("t", inputRowType.ref());
        DBSPExpression body = var.deref().field(operands.get(0));
        DBSPExpression projection = body.closure(var.asParameter());

        DBSPVariablePath origRow = new DBSPVariablePath("origRow", inputRowType.ref());
        DBSPVariablePath delayedRow = new DBSPVariablePath("delayedRow", body.getType().ref());
        DBSPExpression functionBody = new DBSPTupleExpression(delayedRow.deref());
        DBSPExpression function = functionBody.closure(origRow.asParameter(), delayedRow.asParameter());

        DBSPComparatorExpression comparator = new DBSPNoComparatorExpression(node, inputRowType);
        comparator = this.generateComparator(group, comparator);
        return new DBSPLagOperator(
                node, offset, function, projection, comparator,
                this.makeIndexedZSet(input.getOutputIndexedZSetType().keyType, functionBody.getType()), input);
    }

    /**
     * Helper function for isLimit.  Check to see if an expression is a comparison with a constant.
     * @param compared  Expression that is compared.  Check if it is a RexInputRef with the expectedIndex.
     * @param limit     Expression compared against.  Check if it is a RexLiteral with an integer value.
     * @param eq        True if comparison is for equality.
     * @return          -1 if the operands don't have the expected shape.  The limit value otherwise.
     */
    int limitValue(RexNode compared, int expectedIndex, RexNode limit, boolean eq) {
        if (compared instanceof RexInputRef) {
            RexInputRef ri = (RexInputRef) compared;
            if (ri.getIndex() != expectedIndex)
                return -1;
        }
        if (limit instanceof RexLiteral) {
            RexLiteral literal = (RexLiteral) limit;
            SqlTypeName type = literal.getType().getSqlTypeName();
            if (SqlTypeName.INT_TYPES.contains(type)) {
                Integer value = literal.getValueAs(Integer.class);
                Objects.requireNonNull(value);
                if (!eq)
                    value++;
                return value;
            }
        }
        return -1;
    }

    /**
     * Analyze whether an expression has the form row.index <= constant
     * or something equivalent.
     * @param node  Expression to analyze.
     * @param variableIndex  Row index of the field that should be compared
     * @return -1 if the shape is unsuitable.
     *         An integer value representing the limit otherwise.
     */
    int isLimit(RexCall node, int variableIndex) {
        if (node.getOperands().size() != 2)
            return -1;
        RexNode left = node.operands.get(0);
        RexNode right = node.operands.get(1);
        switch (node.getKind()) {
            case EQUALS: {
                // Treat 'a == 1' like 'a <= 1'.
                // This works because the smallest legal limit is 1.
                int leftLimit = limitValue(left, variableIndex, right, true);
                int rightLimit = limitValue(left, variableIndex, right, true);
                if (leftLimit == 1 || rightLimit == 1)
                    return 1;
            }
            case LESS_THAN:
                return limitValue(left, variableIndex, right, false);
            case LESS_THAN_OR_EQUAL:
                return limitValue(left, variableIndex, right, true);
            case GREATER_THAN:
                return limitValue(right, variableIndex, left, false);
            case GREATER_THAN_OR_EQUAL:
                return limitValue(right, variableIndex, left, true);
            default:
                return -1;
        }
    }

    /** Helper class for window processing.
     * Calcite can sometimes use the same group for window computations
     * that we cannot perform in one operator, so we
     * divide some group/window combinations into multiple
     * combinations.
     */
    abstract class GroupAndAggregates {
        final ExpressionCompiler eComp;
        final CalciteObject node;
        final Window window;
        final Window.Group group;
        final List<AggregateCall> aggregateCalls;
        final int windowFieldIndex;
        final DBSPTypeTuple windowResultType;
        final DBSPTypeTuple inputRowType;
        final DBSPVariablePath inputRowRefVar;

        GroupAndAggregates(Window window, Window.Group group,
                           List<AggregateCall> aggregateCalls, int windowFieldIndex) {
            assert !aggregateCalls.isEmpty();
            this.node = CalciteObject.create(window);
            this.window = window;
            this.group = group;
            this.aggregateCalls = new ArrayList<>(aggregateCalls);
            this.windowFieldIndex = windowFieldIndex;
            this.windowResultType = CalciteToDBSPCompiler.this.convertType(
                    window.getRowType(), false).to(DBSPTypeTuple.class);
            this.inputRowType = CalciteToDBSPCompiler.this.convertType(
                    window.getInput().getRowType(), false).to(DBSPTypeTuple.class);
            this.inputRowRefVar = inputRowType.ref().var("t");
            this.eComp = new ExpressionCompiler(inputRowRefVar, window.constants, CalciteToDBSPCompiler.this.compiler);
        }

        abstract DBSPOperator implement(DBSPOperator input, DBSPOperator lastOperator);
    }

    /** A class representing a LEAD or LAG function */
    class LeadLagAggregates extends GroupAndAggregates {
        LeadLagAggregates(Window window, Window.Group group,
                          AggregateCall aggregateCall, int windowFieldIndex) {
            super(window, group, Linq.list(aggregateCall), windowFieldIndex);
        }

        @Override
        DBSPOperator implement(DBSPOperator input, DBSPOperator lastOperator) {
            throw new UnimplementedException(this.node);
        }
    }

    class StandardAggregates extends GroupAndAggregates {
        StandardAggregates(Window window, Window.Group group,
                           List<AggregateCall> aggregateCalls, int windowFieldIndex) {
            super(window, group, aggregateCalls, windowFieldIndex);
        }

        DBSPExpression compileWindowBound(RexWindowBound bound, DBSPType boundType, ExpressionCompiler eComp) {
            IsNumericType numType = boundType.to(IsNumericType.class);
            DBSPExpression numericBound;
            if (bound.isUnbounded())
                numericBound = numType.getMaxValue();
            else if (bound.isCurrentRow())
                numericBound = numType.getZero();
            else {
                DBSPExpression value = eComp.compile(Objects.requireNonNull(bound.getOffset()));
                numericBound = value.cast(boundType);
            }
            String beforeAfter = bound.isPreceding() ? "Before" : "After";
            return new DBSPConstructorExpression(
                    new DBSPPath("RelOffset", beforeAfter).toExpression(),
                    DBSPTypeAny.getDefault(), numericBound);
        }

        @Override
        DBSPOperator implement(DBSPOperator input, DBSPOperator lastOperator) {
            List<RelFieldCollation> orderKeys = group.orderKeys.getFieldCollations();
            List<Integer> partitionKeys = group.keys.toList();
            List<DBSPExpression> expressions = Linq.map(partitionKeys,
                    f -> inputRowRefVar.deepCopy().deref().field(f).applyCloneIfNeeded());
            DBSPTupleExpression partition = new DBSPTupleExpression(node, expressions);

            if (orderKeys.size() > 1)
                throw new UnimplementedException("ORDER BY not yet supported with multiple columns", node);
            RelFieldCollation collation = orderKeys.get(0);
            int orderColumnIndex = collation.getFieldIndex();
            DBSPExpression orderField = inputRowRefVar.deref().field(orderColumnIndex);

            // Map each row to an expression of the form: |t| (partition, (order, (*t).clone()))
            DBSPExpression orderAndRow = new DBSPTupleExpression(
                    orderField, inputRowRefVar.deepCopy().deref().applyClone());
            DBSPExpression mapExpr = new DBSPRawTupleExpression(partition, orderAndRow);
            DBSPClosureExpression mapClo = mapExpr.closure(inputRowRefVar.asParameter());
            DBSPOperator mapIndex = new DBSPMapIndexOperator(node, mapClo,
                    CalciteToDBSPCompiler.this.makeIndexedZSet(partition.getType(), orderAndRow.getType()), input);
            CalciteToDBSPCompiler.this.circuit.addOperator(mapIndex);

            // This operator is always incremental, so create the non-incremental version
            // of it by adding a D and an I around it.
            DBSPDifferentiateOperator diff = new DBSPDifferentiateOperator(node, mapIndex);
            CalciteToDBSPCompiler.this.circuit.addOperator(diff);

            if (collation.getDirection() != RelFieldCollation.Direction.ASCENDING)
                throw new UnimplementedException("OVER only supports ascending sorting", node);
            DBSPType sortType = inputRowType.tupFields[orderColumnIndex];
            if (!sortType.is(DBSPTypeInteger.class) &&
                    !sortType.is(DBSPTypeTimestamp.class) &&
                    !sortType.is(DBSPTypeDate.class))
                throw new UnimplementedException("OVER currently requires an integer type for ordering "
                        + "and cannot handle " + sortType, node);
            if (sortType.mayBeNull) {
                RelDataTypeField relDataTypeField = window.getInput().getRowType().getFieldList().get(orderColumnIndex);
                throw new UnimplementedException("OVER currently does not support sorting on nullable column " +
                        Utilities.singleQuote(relDataTypeField.getName()) + ":", node);
            }

            // Create window description
            DBSPExpression lb = this.compileWindowBound(group.lowerBound, sortType, eComp);
            DBSPExpression ub = this.compileWindowBound(group.upperBound, sortType, eComp);
            DBSPExpression windowExpr = new DBSPConstructorExpression(
                    new DBSPPath("RelRange", "new").toExpression(),
                    DBSPTypeAny.getDefault(), lb, ub);

            List<DBSPType> types = Linq.map(aggregateCalls, c -> CalciteToDBSPCompiler.this.convertType(c.type, false));
            DBSPTypeTuple tuple = new DBSPTypeTuple(types);
            DBSPAggregate fd = CalciteToDBSPCompiler.this.createAggregate(
                    window, aggregateCalls, tuple, inputRowType, 0, ImmutableBitSet.of());

            // Compute aggregates for the window
            DBSPTypeTuple aggResultType = fd.defaultZeroType().to(DBSPTypeTuple.class);
            DBSPOperator windowAgg = new DBSPWindowAggregateOperator(
                    node, null, fd,
                    windowExpr,
                    CalciteToDBSPCompiler.this.makeIndexedZSet(
                            new DBSPTypeTuple(partition.getType(), sortType),
                            aggResultType), diff);
            CalciteToDBSPCompiler.this.circuit.addOperator(windowAgg);

            DBSPIntegrateOperator integral = new DBSPIntegrateOperator(node, windowAgg);
            CalciteToDBSPCompiler.this.circuit.addOperator(integral);

            // Join the previous result with the aggregate
            // First index the aggregate.
            DBSPTypeTuple currentTupleType = lastOperator.getOutputZSetElementType().to(DBSPTypeTuple.class);
            DBSPVariablePath previousRowRefVar = currentTupleType.ref().var("t");
            DBSPExpression partAndOrder = new DBSPTupleExpression(
                    partition.applyCloneIfNeeded(),
                    orderField.applyCloneIfNeeded());
            DBSPExpression indexedInput = new DBSPRawTupleExpression(
                    partAndOrder, previousRowRefVar.deepCopy().deref().applyClone());
            DBSPExpression partAndOrderClo = indexedInput.closure(previousRowRefVar.asParameter());
            DBSPOperator indexInput = new DBSPIndexOperator(node, partAndOrderClo,
                    CalciteToDBSPCompiler.this.makeIndexedZSet(partAndOrder.getType(), previousRowRefVar.getType().deref()),
                    lastOperator.isMultiset, lastOperator);
            CalciteToDBSPCompiler.this.circuit.addOperator(indexInput);

            DBSPVariablePath key = partAndOrder.getType().ref().var("k");
            DBSPVariablePath left = currentTupleType.ref().var("l");
            DBSPVariablePath right = aggResultType.ref().var("r");
            DBSPExpression[] allFields = new DBSPExpression[
                    currentTupleType.size() + aggResultType.size()];
            for (int i = 0; i < currentTupleType.size(); i++)
                allFields[i] = left.deref().field(i).applyCloneIfNeeded();
            for (int i = 0; i < aggResultType.size(); i++) {
                // Calcite is very smart and sometimes infers non-nullable result types
                // for these aggregates.  So we have to cast the results to whatever
                // Calcite says they will be.
                allFields[i + currentTupleType.size()] = right.deref().field(i).applyCloneIfNeeded().cast(
                        windowResultType.getFieldType(this.windowFieldIndex + i));
            }
            DBSPTupleExpression addExtraFieldBody = new DBSPTupleExpression(allFields);
            DBSPClosureExpression addExtraField =
                    addExtraFieldBody.closure(key.asParameter(), left.asParameter(), right.asParameter());
            return new DBSPStreamJoinOperator(node, CalciteToDBSPCompiler.this.makeZSet(addExtraFieldBody.getType()),
                    addExtraField, indexInput.isMultiset || windowAgg.isMultiset, indexInput, integral);
        }
    }

    /** Decompose a window into a list of GroupAndAggregates that can be each implemented
     * by a separate DBSP operator. */
    List<GroupAndAggregates> splitWindow(LogicalWindow window, int windowFieldIndex) {
        List<GroupAndAggregates> result = new ArrayList<>();
        for (Window.Group group: window.groups) {
            List<AggregateCall> calls = group.getAggregateCalls(window);
            List<AggregateCall> nonLeadLag = new ArrayList<>();
            // Must keep call in the same order as in the original list
            for (AggregateCall call: calls) {
                SqlKind kind = call.getAggregation().getKind();
                if (kind == SqlKind.LAG || kind == SqlKind.LEAD) {
                    if (!nonLeadLag.isEmpty()) {
                        // Add everything accumulated so far
                        result.add(new StandardAggregates(window, group, nonLeadLag, windowFieldIndex));
                        windowFieldIndex += nonLeadLag.size();
                        nonLeadLag.clear();
                    }
                    result.add(new LeadLagAggregates(window, group, call, windowFieldIndex));
                    windowFieldIndex += 1;
                } else {
                    nonLeadLag.add(call);
                }
            }
            if (!nonLeadLag.isEmpty()) {
                result.add(new StandardAggregates(window, group, nonLeadLag, windowFieldIndex));
                windowFieldIndex += nonLeadLag.size();
                nonLeadLag.clear();
            }
        }
        return result;
    }

    public void visitWindow(LogicalWindow window) {
        DBSPOperator input = this.getInputAs(window.getInput(0), true);
        DBSPTypeTuple inputRowType = this.convertType(
                window.getInput().getRowType(), false).to(DBSPTypeTuple.class);
        int windowFieldIndex = inputRowType.size();

        // Special handling for the following pattern:
        // LogicalFilter(condition=[<=(RANK_COLUMN, LIMIT)])
        // LogicalWindow(window#0=[window(partition ... order by ... aggs [RANK()])])
        // This is compiled into a TopK over each group.
        // There is no way this can be expressed otherwise in SQL or using RelNode.
        // This works for RANK, DENSE_RANK, and ROW_NUMBER.
        if (window.groups.size() == 1 && !this.ancestors.isEmpty()) {
            Window.Group group = window.groups.get(0);
            if (group.aggCalls.size() == 1) {
                Window.RexWinAggCall agg = group.aggCalls.get(0);
                RelNode parent = this.getParent();
                SqlOperator operator = agg.getOperator();
                // Aggregate functions seem always to be at the end.
                // The window always seems to collect all fields.
                int aggregationArgumentIndex = inputRowType.size();
                if (operator instanceof SqlRankFunction) {
                    SqlRankFunction rank = (SqlRankFunction) operator;
                    if (parent instanceof LogicalFilter) {
                        LogicalFilter filter = (LogicalFilter) parent;
                        RexNode condition = filter.getCondition();
                        if (condition instanceof RexCall) {
                            int limit = this.isLimit((RexCall) condition, aggregationArgumentIndex);
                            if (limit >= 0) {
                                this.generateNestedTopK(window, group, limit, rank.kind);
                                return;
                            }
                        }
                    }
                }
            }
        }

        // We have to process multiple Groups, and each group has multiple aggregates.
        // The final result is accumulated using join operators, which just keep adding columns to
        // the "lastOperator".  The "lastOperator" is initially the input node itself.
        DBSPOperator lastOperator = input;
        List<GroupAndAggregates> toProcess = this.splitWindow(window, windowFieldIndex);
        for (GroupAndAggregates ga: toProcess) {
            if (lastOperator != input)
                this.circuit.addOperator(lastOperator);
            lastOperator = ga.implement(input, lastOperator);
        }
        this.assignOperator(window, lastOperator);
    }

    private RelNode getParent() {
        return Utilities.last(this.ancestors);
    }

    public void visitSort(LogicalSort sort) {
        CalciteObject node = CalciteObject.create(sort);
        RelNode input = sort.getInput();
        DBSPOperator opInput = this.getOperator(input);
        if (this.options.languageOptions.ignoreOrderBy && sort.fetch == null) {
            Utilities.putNew(this.nodeOperator, sort, opInput);
            return;
        }
        if (sort.offset != null)
            throw new UnimplementedException(node);

        DBSPExpression limit = null;
        if (sort.fetch != null) {
            ExpressionCompiler expressionCompiler = new ExpressionCompiler(null, this.compiler);
            // We expect the limit to be a constant
            limit = expressionCompiler.compile(sort.fetch);
        }

        DBSPType inputRowType = this.convertType(input.getRowType(), false);
        DBSPVariablePath t = inputRowType.ref().var("t");
        DBSPExpression emptyGroupKeys =
                new DBSPRawTupleExpression(
                        new DBSPRawTupleExpression(),
                        DBSPTupleExpression.flatten(t.deref())).closure(t.asParameter());
        DBSPOperator index = new DBSPIndexOperator(
                node, emptyGroupKeys,
                this.makeIndexedZSet(new DBSPTypeRawTuple(), inputRowType),
                opInput.isMultiset, opInput);
        this.circuit.addOperator(index);

        // Generate comparison function for sorting the vector
        DBSPComparatorExpression comparator = new DBSPNoComparatorExpression(node, inputRowType);
        for (RelFieldCollation collation : sort.getCollation().getFieldCollations()) {
            int field = collation.getFieldIndex();
            RelFieldCollation.Direction direction = collation.getDirection();
            boolean ascending = switch (direction) {
                case ASCENDING -> true;
                case DESCENDING -> false;
                default -> throw new UnimplementedException(node);
            };
            comparator = new DBSPFieldComparatorExpression(node, comparator, field, ascending);
        }

        if (sort.fetch != null) {
            // TopK operator.
            // Since TopK is always incremental we have to wrap it into a D-I pair
            DBSPDifferentiateOperator diff = new DBSPDifferentiateOperator(node, index);
            this.circuit.addOperator(diff);
            DBSPIndexedTopKOperator topK = new DBSPIndexedTopKOperator(
                    node, DBSPIndexedTopKOperator.TopKNumbering.ROW_NUMBER,
                    comparator, limit, null, diff);
            this.circuit.addOperator(topK);
            DBSPIntegrateOperator integral = new DBSPIntegrateOperator(node, topK);
            this.circuit.addOperator(integral);
            // If we ignore ORDER BY this is the result.
            if (this.options.languageOptions.ignoreOrderBy) {
                // We must drop the index we built.
                DBSPDeindexOperator deindex = new DBSPDeindexOperator(node, integral);
                this.assignOperator(sort, deindex);
                return;
            }
            // Otherwise we have to sort again in a vector!
            // Fall through, continuing from the integral.
            index = integral;
        }
        // Global sort.  Implemented by aggregate in a single Vec<> which is then sorted.
        // Apply an aggregation function that just creates a vector.
        DBSPTypeVec vecType = new DBSPTypeVec(inputRowType, false);
        DBSPExpression zero = new DBSPPath(vecType.name, "new").toExpression().call();
        DBSPVariablePath accum = vecType.ref(true).var("a");
        DBSPVariablePath row = inputRowType.ref().var("v");
        // An element with weight 'w' is pushed 'w' times into the vector
        DBSPExpression wPush = new DBSPApplyExpression(node,
                "weighted_push", new DBSPTypeVoid(), accum, row, this.compiler.weightVar);
        DBSPExpression push = wPush.closure(
                accum.asParameter(), row.asParameter(),
                this.compiler.weightVar.asParameter());
        DBSPExpression constructor =
                new DBSPPath(
                        new DBSPSimplePathSegment("Fold",
                                DBSPTypeAny.getDefault(),
                                DBSPTypeAny.getDefault(),
                                new DBSPTypeUser(node, USER, "UnimplementedSemigroup",
                                        false, DBSPTypeAny.getDefault()),
                                DBSPTypeAny.getDefault(),
                                DBSPTypeAny.getDefault()),
                        new DBSPSimplePathSegment("new")).toExpression();

        DBSPExpression folder = constructor.call(zero, push);
        DBSPStreamAggregateOperator agg = new DBSPStreamAggregateOperator(node,
                this.makeIndexedZSet(new DBSPTypeRawTuple(), new DBSPTypeVec(inputRowType, false)),
                folder, null, index, false);
        this.circuit.addOperator(agg);

        DBSPSortExpression sorter = new DBSPSortExpression(node, inputRowType, comparator);
        DBSPOperator result = new DBSPMapOperator(
                node, sorter, this.makeZSet(vecType), agg);
        this.assignOperator(sort, result);
    }

    @Override
    public void visit(
            RelNode node, int ordinal,
            @Nullable RelNode parent) {
        Logger.INSTANCE.belowLevel(this, 3)
                .append("Visiting ")
                .append(node.toString())
                .newline();
        if (this.nodeOperator.containsKey(node))
            // We have already done this one.  This can happen because the
            // plan can be a DAG, not just a tree.
            return;

        // logical correlates are not done in postorder.
        if (this.visitIfMatches(node, LogicalCorrelate.class, this::visitCorrelate))
            return;

        this.ancestors.add(node);
        // First process children
        super.visit(node, ordinal, parent);
        RelNode last = Utilities.removeLast(this.ancestors);
        assert last == node: "Corrupted stack: got " + last + " expected " + node;

        // Synthesize current node
        boolean success =
                this.visitIfMatches(node, LogicalTableScan.class, l -> this.visitScan(l, false)) ||
                this.visitIfMatches(node, JdbcTableScan.class, l -> this.visitScan(l, true)) ||
                this.visitIfMatches(node, LogicalProject.class, this::visitProject) ||
                this.visitIfMatches(node, LogicalUnion.class, this::visitUnion) ||
                this.visitIfMatches(node, LogicalMinus.class, this::visitMinus) ||
                this.visitIfMatches(node, LogicalFilter.class, this::visitFilter) ||
                this.visitIfMatches(node, LogicalValues.class, this::visitLogicalValues) ||
                this.visitIfMatches(node, LogicalAggregate.class, this::visitAggregate) ||
                this.visitIfMatches(node, LogicalJoin.class, this::visitJoin) ||
                this.visitIfMatches(node, LogicalIntersect.class, this::visitIntersect) ||
                this.visitIfMatches(node, LogicalWindow.class, this::visitWindow) ||
                this.visitIfMatches(node, LogicalSort.class, this::visitSort) ||
                this.visitIfMatches(node, Uncollect.class, this::visitUncollect);
        if (!success)
            throw new UnimplementedException(CalciteObject.create(node));
    }

    InputColumnMetadata convertMetadata(RelColumnMetadata metadata) {
        DBSPType type = this.convertType(metadata.getType(), false);
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(null, this.compiler);
        DBSPExpression lateness = null;
        if (metadata.lateness != null)
            lateness = expressionCompiler.compile(metadata.lateness);
        DBSPExpression defaultValue = null;
        if (metadata.defaultValue != null)
            defaultValue = expressionCompiler.compile(metadata.defaultValue).cast(type);
        return new InputColumnMetadata(metadata.getNode(), metadata.getName(), type,
                metadata.isPrimaryKey, lateness, defaultValue);
    }

    @SuppressWarnings("UnusedReturnValue")
    @Nullable
    public DBSPNode compile(FrontEndStatement statement) {
        if (statement.is(CreateViewStatement.class)) {
            CreateViewStatement view = statement.to(CreateViewStatement.class);
            RelNode rel = view.getRelNode();
            Logger.INSTANCE.belowLevel(this, 2)
                    .append(CalciteCompiler.getPlan(rel))
                    .newline();
            this.go(rel);
            DBSPOperator op = this.getOperator(rel);

            // The operator above may not contain all columns that were computed.
            RelRoot root = view.getRoot();
            DBSPTypeZSet producedType = op.getOutputZSetType();
            // The output element may be a Tuple or a Vec<Tuple> when we sort;
            DBSPType elemType = producedType.getElementType();
            DBSPTypeTupleBase tuple;
            boolean isVector = false;
            if (elemType.is(DBSPTypeVec.class)) {
                isVector = true;
                tuple = elemType.to(DBSPTypeVec.class).getElementType().to(DBSPTypeTupleBase.class);
            } else {
                tuple = elemType.to(DBSPTypeTupleBase.class);
            }
            if (root.fields.size() != tuple.size()) {
                DBSPVariablePath t = new DBSPVariablePath("t", tuple.ref());
                List<DBSPExpression> resultFields = new ArrayList<>();
                for (Map.Entry<Integer, String> field: root.fields) {
                    resultFields.add(t.deref().field(field.getKey()).applyCloneIfNeeded());
                }
                DBSPExpression all = new DBSPTupleExpression(resultFields, false);
                DBSPClosureExpression closure = all.closure(t.asParameter());
                DBSPType outputElementType = all.getType();
                if (isVector) {
                    outputElementType = new DBSPTypeVec(outputElementType, false);
                    DBSPVariablePath v = new DBSPVariablePath("v", elemType);
                    closure = new DBSPApplyExpression("map", outputElementType, v, closure)
                            .closure(v.asParameter());
                }
                op = new DBSPMapOperator(view.getCalciteObject(), closure, this.makeZSet(outputElementType), op);
                this.circuit.addOperator(op);
            }

            DBSPOperator o;
            if (this.generateOutputForNextView) {
                this.metadata.addView(view);
                DBSPType originalRowType = this.convertType(view.getRoot().validatedRowType, true);
                DBSPTypeStruct struct = originalRowType.to(DBSPTypeStruct.class).rename(view.relationName);
                o = new DBSPSinkOperator(
                        view.getCalciteObject(), view.relationName,
                        view.statement,
                        struct, statement.comment, op);
            } else {
                // We may already have a node for this output
                DBSPOperator previous = this.circuit.getOutput(view.relationName);
                if (previous != null)
                    return previous;
                o = new DBSPNoopOperator(view.getCalciteObject(), op, statement.comment, view.relationName);
            }
            this.circuit.addOperator(o);
            return o;
        } else if (statement.is(CreateTableStatement.class) ||
                statement.is(DropTableStatement.class)) {
            boolean success = this.tableContents.execute(statement);
            if (!success)
                return null;
            CreateTableStatement create = statement.as(CreateTableStatement.class);
            if (create != null) {
                // We create an input for the circuit.
                String tableName = create.relationName;
                CreateTableStatement def = this.tableContents.getTableDefinition(tableName);
                DBSPType rowType = def.getRowTypeAsTuple(this.compiler.getTypeCompiler());
                DBSPTypeStruct originalRowType = def.getRowTypeAsStruct(this.compiler.getTypeCompiler());
                CalciteObject identifier = CalciteObject.EMPTY;
                if (create.node instanceof SqlCreateTable) {
                    SqlCreateTable sct = (SqlCreateTable)create.node;
                    identifier = CalciteObject.create(sct.name);
                }
                List<InputColumnMetadata> metadata = Linq.map(create.columns, this::convertMetadata);
                InputTableMetadata tableMeta = new InputTableMetadata(metadata);
                DBSPSourceMultisetOperator result = new DBSPSourceMultisetOperator(
                        create.getCalciteObject(), identifier, this.makeZSet(rowType), originalRowType,
                        def.statement, tableMeta, tableName);
                this.circuit.addOperator(result);
                this.metadata.addTable(create);
            }
            return null;
        } else if (statement.is(TableModifyStatement.class)) {
            TableModifyStatement modify = statement.to(TableModifyStatement.class);
            // The type of the data must be extracted from the modified table
            boolean isInsert = modify.insert;
            SqlNodeList targetColumnList;
            if (isInsert) {
                SqlInsert insert = (SqlInsert) statement.node;
                targetColumnList = insert.getTargetColumnList();
            } else {
                SqlRemove remove = (SqlRemove) statement.node;
                targetColumnList = remove.getTargetColumnList();
            }
            CreateTableStatement def = this.tableContents.getTableDefinition(modify.tableName);
            this.modifyTableTranslation = new ModifyTableTranslation(
                    modify, def, targetColumnList, this.compiler);
            DBSPZSetLiteral result;
            if (modify.rel instanceof LogicalTableScan) {
                // Support for INSERT INTO table (SELECT * FROM otherTable)
                LogicalTableScan scan = (LogicalTableScan) modify.rel;
                List<String> name = scan.getTable().getQualifiedName();
                String sourceTable = name.get(name.size() - 1);
                result = this.tableContents.getTableContents(sourceTable);
            } else if (modify.rel instanceof LogicalValues) {
                this.go(modify.rel);
                result = this.modifyTableTranslation.getTranslation();
            } else if (modify.rel instanceof LogicalProject) {
                // The Calcite optimizer is not able to convert a projection
                // of a VALUES statement that contains ARRAY constructors,
                // because there is no array literal in Calcite.  These expressions
                // are left as projections in the code.  We only handle
                // the case where all project expressions are "constants".
                result = this.compileConstantProject((LogicalProject) modify.rel);
            } else {
                throw new UnimplementedException(statement.getCalciteObject());
            }
            if (!isInsert)
                result = result.negate();
            this.modifyTableTranslation = null;
            this.tableContents.addToTable(modify.tableName, result);
            return result;
        } else if (statement.is(CreateTypeStatement.class)) {
            CreateTypeStatement stat = statement.to(CreateTypeStatement.class);
            CalciteObject object = CalciteObject.create(stat.createType.name);
            SqlCreateType ct = stat.createType;
            int index = 0;
            List<RelDataTypeField> relFields = stat.relDataType.getFieldList();
            List<DBSPTypeStruct.Field> fields = new ArrayList<>();
            for (SqlNode def : Objects.requireNonNull(ct.attributeDefs)) {
                DBSPType fieldType;
                final SqlAttributeDefinition attributeDef =
                        (SqlAttributeDefinition) def;
                final SqlDataTypeSpec typeSpec = attributeDef.dataType;
                if (typeSpec.getTypeNameSpec() instanceof SqlUserDefinedTypeNameSpec) {
                    // Reference to another struct
                    SqlIdentifier identifier = typeSpec.getTypeNameSpec().getTypeName();
                    String referred = identifier.getSimple();
                    fieldType = this.compiler.getStructByName(referred);
                } else {
                    RelDataTypeField ft = relFields.get(index);
                    fieldType = this.convertType(ft.getType(), true);
                }
                DBSPTypeStruct.Field field = new DBSPTypeStruct.Field(
                        object,
                        attributeDef.name.getSimple(),
                        index++,
                        fieldType,
                        Utilities.identifierIsQuoted(attributeDef.name));
                fields.add(field);
            }

            String saneName = this.compiler.getSaneStructName(stat.typeName);
            DBSPTypeStruct struct = new DBSPTypeStruct(object, stat.typeName, saneName, fields, false);
            this.compiler.registerStruct(struct);
            DBSPItem item = new DBSPStructItem(struct);
            this.circuit.addDeclaration(new DBSPDeclaration(item));
            return null;
        } else if (statement.is(CreateFunctionStatement.class)) {
            CreateFunctionStatement stat = statement.to(CreateFunctionStatement.class);
            DBSPFunction function = stat.function.getImplementation(this.compiler.getTypeCompiler(), this.compiler);
            if (function != null) {
                this.circuit.addDeclaration(new DBSPDeclaration(new DBSPFunctionItem(function)));
            }
            return null;
        }
        throw new UnimplementedException(statement.getCalciteObject());
    }

    private DBSPZSetLiteral compileConstantProject(LogicalProject project) {
        // Specialization of the visitor's visit method for LogicalProject
        // Does not produce a DBSPOperator, but only a literal.
        CalciteObject node = CalciteObject.create(project);
        DBSPType outputElementType = this.convertType(project.getRowType(), false);
        DBSPTypeTuple tuple = outputElementType.to(DBSPTypeTuple.class);
        DBSPType inputType = this.convertType(project.getInput().getRowType(), false);
        DBSPVariablePath row = inputType.ref().var("t");  // should not be used
        ExpressionCompiler expressionCompiler = new ExpressionCompiler(row, this.compiler);
        DBSPZSetLiteral result = DBSPZSetLiteral.emptyWithElementType(outputElementType);

        List<DBSPExpression> resultColumns = new ArrayList<>();
        int index = 0;
        for (RexNode column : project.getProjects()) {
            DBSPExpression exp = expressionCompiler.compile(column);
            DBSPType expectedType = tuple.getFieldType(index);
            if (!exp.getType().sameType(expectedType)) {
                // Calcite's optimizations do not preserve types!
                exp = exp.applyCloneIfNeeded().cast(expectedType);
            }
            resultColumns.add(exp);
            index++;
        }

        DBSPExpression exp = new DBSPTupleExpression(node, resultColumns);
        result.add(exp);
        return result;
    }

    public TableContents getTableContents() {
        return this.tableContents;
    }

    public void clearTables() {
        this.tableContents.clear();
    }
}
