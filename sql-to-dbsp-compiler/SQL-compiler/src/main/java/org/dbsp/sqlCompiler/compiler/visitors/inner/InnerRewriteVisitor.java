package org.dbsp.sqlCompiler.compiler.visitors.inner;

import org.dbsp.sqlCompiler.ir.IDBSPInnerNode;
import org.dbsp.sqlCompiler.compiler.IErrorReporter;
import org.dbsp.sqlCompiler.compiler.visitors.VisitDecision;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitRewriter;
import org.dbsp.sqlCompiler.ir.DBSPAggregate;
import org.dbsp.sqlCompiler.ir.DBSPFunction;
import org.dbsp.sqlCompiler.ir.DBSPParameter;
import org.dbsp.sqlCompiler.ir.expression.*;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPBoolLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPDateLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPDecimalLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPDoubleLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPFloatLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPGeoPointLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI16Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI32Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPI64Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPISizeLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPIntervalMillisLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPIntervalMonthsLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPKeywordLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPNullLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPStrLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPStringLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPTimeLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPTimestampLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPU32Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPU64Literal;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPUSizeLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPVecLiteral;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPZSetLiteral;
import org.dbsp.sqlCompiler.ir.statement.DBSPComment;
import org.dbsp.sqlCompiler.ir.statement.DBSPConstItem;
import org.dbsp.sqlCompiler.ir.statement.DBSPExpressionStatement;
import org.dbsp.sqlCompiler.ir.statement.DBSPLetStatement;
import org.dbsp.sqlCompiler.ir.statement.DBSPStatement;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeStream;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeAny;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeFunction;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeIndexedZSet;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeRawTuple;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeRef;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeStruct;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeTuple;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeUser;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeVec;
import org.dbsp.sqlCompiler.ir.type.DBSPTypeZSet;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBaseType;
import org.dbsp.util.IWritesLogs;
import org.dbsp.util.Linq;
import org.dbsp.util.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for Inner visitors which rewrite expressions, types, and statements.
 * This class recurses over the structure of expressions, types, and statements
 * and if any fields have changed builds a new version of the object.  Classes
 * that extend this should override the preorder methods and ignore the postorder
 * methods.
 */
public abstract class InnerRewriteVisitor
        extends InnerVisitor
        implements IWritesLogs {
    protected InnerRewriteVisitor(IErrorReporter reporter) {
        super(reporter);
    }

    /**
     * Result produced by the last preorder invocation.
     */
    @Nullable
    protected IDBSPInnerNode lastResult;

    IDBSPInnerNode getResult() {
        return Objects.requireNonNull(this.lastResult);
    }

    @Override
    public IDBSPInnerNode apply(IDBSPInnerNode dbspNode) {
        this.startVisit();
        dbspNode.accept(this);
        this.endVisit();
        return this.getResult();
    }

    /**
     * Replace the 'old' IR node with the 'newOp' IR node if
     * any of its fields differs.
     */
    protected void map(IDBSPInnerNode old, IDBSPInnerNode newOp) {
        if (old == newOp || old.sameFields(newOp)) {
            // Ignore new op.
            this.lastResult = old;
            return;
        }

        Logger.INSTANCE.belowLevel(this, 1)
                .append(this.toString())
                .append(":")
                .appendSupplier(old::toString)
                .append(" -> ")
                .appendSupplier(newOp::toString)
                .newline();
        this.lastResult = newOp;
    }

    @Override
    public VisitDecision preorder(IDBSPInnerNode node) {
        this.map(node, node);
        return VisitDecision.STOP;
    }

    protected DBSPExpression getResultExpression() {
        return this.getResult().to(DBSPExpression.class);
    }

    protected DBSPType getResultType() { return this.getResult().to(DBSPType.class); }

    @Nullable
    protected DBSPExpression transformN(@Nullable DBSPExpression expression) {
        if (expression == null)
            return null;
        return this.transform(expression);
    }

    protected DBSPExpression transform(DBSPExpression expression) {
        expression.accept(this);
        return this.getResultExpression();
    }

    protected DBSPExpression[] transform(DBSPExpression[] expressions) {
        return Linq.map(expressions, this::transform, DBSPExpression.class);
    }

    protected DBSPStatement transform(DBSPStatement statement) {
        statement.accept(this);
        return this.getResult().to(DBSPStatement.class);
    }

    protected DBSPType transform(DBSPType type) {
        type.accept(this);
        return this.getResultType();
    }

    protected DBSPType[] transform(DBSPType[] expressions) {
        return Linq.map(expressions, this::transform, DBSPType.class);
    }

    /////////////////////// Types ////////////////////////////////

    @Override
    public VisitDecision preorder(DBSPTypeAny type) {
        this.map(type, type);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeBaseType type) {
        this.map(type, type);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeFunction type) {
        this.push(type);
        DBSPType resultType = this.transform(type.resultType);
        DBSPType[] argTypes = this.transform(type.argumentTypes);
        this.pop(type);
        DBSPType result = new DBSPTypeFunction(resultType, argTypes);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeIndexedZSet type) {
        this.push(type);
        DBSPType keyType = this.transform(type.keyType);
        DBSPType elementType = this.transform(type.elementType);
        DBSPType weightType = this.transform(type.weightType);
        this.pop(type);
        DBSPType result = new DBSPTypeIndexedZSet(type.getNode(), keyType, elementType, weightType);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeRawTuple type) {
        this.push(type);
        DBSPType[] elements = this.transform(type.tupFields);
        this.pop(type);
        DBSPType result = new DBSPTypeRawTuple(elements);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeTuple type) {
        this.push(type);
        DBSPType[] elements = this.transform(type.tupFields);
        this.pop(type);
        DBSPType result = new DBSPTypeTuple(elements);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeRef type) {
        this.push(type);
        DBSPType field = this.transform(type.type);
        this.pop(type);
        DBSPType result = new DBSPTypeRef(field, type.mutable);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeUser type) {
        this.push(type);
        DBSPType[] args = this.transform(type.typeArgs);
        this.pop(type);
        DBSPType result = new DBSPTypeUser(type.getNode(), type.name, type.mayBeNull, args);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeStream type) {
        this.push(type);
        DBSPType elementType = this.transform(type.elementType);
        this.pop(type);
        DBSPType result = new DBSPTypeStream(elementType);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeStruct type) {
        this.push(type);
        List<DBSPTypeStruct.Field> fields = Linq.map(
                type.args, f -> new DBSPTypeStruct.Field(f.getNode(), f.name, this.transform(f.type)));
        this.pop(type);
        DBSPType result = new DBSPTypeStruct(type.getNode(), type.name, fields);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeVec type) {
        this.push(type);
        DBSPType elementType = this.transform(type.getElementType());
        this.pop(type);
        DBSPType result = new DBSPTypeVec(elementType);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTypeZSet type) {
        this.push(type);
        DBSPType elementType = this.transform(type.elementType);
        DBSPType weightType = this.transform(type.weightType);
        this.pop(type);
        DBSPType result = new DBSPTypeZSet(type.getNode(), elementType, weightType);
        this.map(type, result);
        return VisitDecision.STOP;
    }

    /////////////////////// Expressions //////////////////////////

    @Override
    public VisitDecision preorder(DBSPBoolLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPBoolLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPDateLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPDateLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPDecimalLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPDecimalLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPDoubleLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPDoubleLiteral(
                expression.getNode(), type, expression.value, expression.raw);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPFloatLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPFloatLiteral(
                expression.getNode(), type, expression.value, expression.raw);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPGeoPointLiteral expression) {
        this.push(expression);
        @Nullable DBSPExpression left = this.transformN(expression.left);
        @Nullable DBSPExpression right = this.transformN(expression.right);
        this.pop(expression);
        DBSPExpression result = new DBSPGeoPointLiteral(expression.getNode(), left, right);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPI16Literal expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPI16Literal(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPI32Literal expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPI32Literal(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPI64Literal expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPI64Literal(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPIntervalMillisLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPIntervalMillisLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPIntervalMonthsLiteral expression) {
        this.pop(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPIntervalMonthsLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPISizeLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPISizeLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPKeywordLiteral expression) {
        this.map(expression, expression);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPNullLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPNullLiteral(expression.getNode(), type, null);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPStringLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPStringLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPStrLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPStrLiteral(expression.getNode(), type, expression.value, expression.raw);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTimeLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPTimeLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTimestampLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPTimestampLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPU32Literal expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPU32Literal(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPU64Literal expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPU64Literal(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPUSizeLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPUSizeLiteral(expression.getNode(), type, expression.value);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPVecLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        List<DBSPExpression> data = null;
        if (expression.data != null)
            data = Linq.map(expression.data, this::transform);
        this.pop(expression);
        DBSPExpression result = new DBSPVecLiteral(expression.getNode(), type, data);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPZSetLiteral expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        Map<DBSPExpression, Long> newData = new HashMap<>();
        for (Map.Entry<DBSPExpression, Long> entry: expression.data.data.entrySet()) {
            DBSPExpression row = this.transform(entry.getKey());
            newData.put(row, entry.getValue());
        }
        DBSPType elementType = this.transform(expression.data.elementType);
        this.pop(expression);

        DBSPZSetLiteral.Contents newContents = new DBSPZSetLiteral.Contents(newData, elementType);
        DBSPExpression result = new DBSPZSetLiteral(expression.getNode(), type, newContents);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPFlatmap expression) {
        this.push(expression);
        DBSPTypeTuple inputElementType = this.transform(expression.inputElementType).to(DBSPTypeTuple.class);
        DBSPType indexType = null;
        if (expression.indexType != null)
            indexType = this.transform(expression.indexType);
        this.pop(expression);
        DBSPExpression result = new DBSPFlatmap(expression.getNode(), inputElementType,
                    expression.collectionFieldIndex, expression.outputFieldIndexes,
                    indexType);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPAsExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.source);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPAsExpression(source, type);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPApplyExpression expression) {
        this.push(expression);
        DBSPExpression[] arguments = this.transform(expression.arguments);
        DBSPExpression function = this.transform(expression.function);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPApplyExpression(function, type, arguments);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPApplyMethodExpression expression) {
        this.push(expression);
        DBSPExpression[] arguments = this.transform(expression.arguments);
        DBSPExpression function = this.transform(expression.function);
        DBSPExpression self = this.transform(expression.self);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPApplyMethodExpression(function, type, self, arguments);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPAssignmentExpression expression) {
        this.push(expression);
        DBSPExpression left = this.transform(expression.left);
        DBSPExpression right = this.transform(expression.right);
        this.pop(expression);
        DBSPExpression result = new DBSPAssignmentExpression(left, right);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPBinaryExpression expression) {
        this.push(expression);
        DBSPExpression left = this.transform(expression.left);
        DBSPExpression right = this.transform(expression.right);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPBinaryExpression(expression.getNode(), type,
                    expression.operation, left, right, expression.primitive);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPBlockExpression expression) {
        this.push(expression);
        List<DBSPStatement> body = Linq.map(expression.contents, this::transform);
        DBSPExpression last = this.transformN(expression.lastExpression);
        this.pop(expression);
        DBSPExpression result = new DBSPBlockExpression(body, last);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPBorrowExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.expression);
        this.pop(expression);
        DBSPExpression result = source.borrow(expression.mut);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPSomeExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.expression);
        this.pop(expression);
        DBSPExpression result = new DBSPSomeExpression(expression.getNode(), source);
        this.map(expression, result);
        return VisitDecision.STOP;
    }


    @Override
    public VisitDecision preorder(DBSPCastExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.source);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = source.cast(type);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPIsNullExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.expression);
        this.pop(expression);
        DBSPExpression result = source.is_null();
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPClosureExpression expression) {
        this.push(expression);
        DBSPExpression body = this.transform(expression.body);
        DBSPParameter[] parameters = Linq.map(
                expression.parameters, p -> {
                    p.accept(this);
                    return this.getResult().to(DBSPParameter.class);
                }, DBSPParameter.class);
        this.pop(expression);
        DBSPExpression result = body.closure(parameters);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPIndexExpression expression) {
        this.push(expression);
        DBSPExpression array = this.transform(expression.array);
        DBSPExpression index = this.transform(expression.index);
        this.pop(expression);
        DBSPExpression result = new DBSPIndexExpression(expression.getNode(), array, index, expression.startsAtOne);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPFieldComparatorExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.source);
        this.pop(expression);
        DBSPExpression result = new DBSPFieldComparatorExpression(
                    expression.getNode(), source.to(DBSPComparatorExpression.class),
                    expression.fieldNo, expression.ascending);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPNoComparatorExpression expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.tupleType);
        this.pop(expression);
        DBSPExpression result = new DBSPNoComparatorExpression(expression.getNode(), type);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPDerefExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.expression);
        this.pop(expression);
        DBSPExpression result = new DBSPDerefExpression(source);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPEnumValue expression) {
        this.map(expression, expression);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPFieldExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.expression);
        this.pop(expression);
        DBSPExpression result = source.field(expression.fieldNo);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPForExpression expression) {
        this.push(expression);
        DBSPExpression iterated = this.transform(expression.iterated);
        DBSPExpression body = this.transform(expression.block);
        DBSPBlockExpression block;
        if (body.is(DBSPBlockExpression.class))
            block = body.to(DBSPBlockExpression.class);
        else
            block = new DBSPBlockExpression(Linq.list(), body);
        this.pop(expression);
        DBSPExpression result = new DBSPForExpression(expression.pattern, iterated, block);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPIfExpression expression) {
        this.push(expression);
        DBSPExpression cond = this.transform(expression.condition);
        DBSPExpression positive = this.transform(expression.positive);
        DBSPExpression negative = this.transform(expression.negative);
        this.pop(expression);
        DBSPExpression result = new DBSPIfExpression(expression.getNode(), cond, positive, negative);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPMatchExpression expression) {
        this.push(expression);
        List<DBSPExpression> caseExpressions =
                Linq.map(expression.cases, c -> this.transform(c.result));
        DBSPExpression matched = this.transform(expression.matched);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        List<DBSPMatchExpression.Case> newCases = Linq.zipSameLength(expression.cases, caseExpressions,
                (c0, e) -> new DBSPMatchExpression.Case(c0.against, e));
        DBSPExpression result = new DBSPMatchExpression(matched, newCases, type);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPPathExpression expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPPathExpression(type, expression.path);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPQualifyTypeExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.expression);
        DBSPType[] types = this.transform(expression.types);
        this.pop(expression);
        DBSPExpression result = new DBSPQualifyTypeExpression(source, types);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPRawTupleExpression expression) {
        this.push(expression);
        DBSPExpression[] fields = this.transform(expression.fields);
        this.pop(expression);
        DBSPExpression result = new DBSPRawTupleExpression(fields);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPSortExpression expression) {
        this.push(expression);
        DBSPExpression comparator = this.transform(expression.comparator);
        DBSPType elementType = this.transform(expression.elementType);
        this.pop(expression);
        DBSPExpression result = new DBSPSortExpression(
                expression.getNode(), elementType, comparator.to(DBSPComparatorExpression.class));
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPStructExpression expression) {
        this.push(expression);
        DBSPExpression function = this.transform(expression.function);
        DBSPExpression[] arguments = this.transform(expression.arguments);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPStructExpression(function, type, arguments);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPTupleExpression expression) {
        this.push(expression);
        DBSPExpression[] fields = this.transform(expression.fields);
        this.pop(expression);
        DBSPExpression result = new DBSPTupleExpression(fields);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPUnaryExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.source);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPUnaryExpression(expression.getNode(), type,
                    expression.operation, source);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPCloneExpression expression) {
        this.push(expression);
        DBSPExpression source = this.transform(expression.expression);
        this.pop(expression);
        DBSPExpression result = new DBSPCloneExpression(expression.getNode(), source);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPVariablePath expression) {
        this.push(expression);
        DBSPType type = this.transform(expression.getType());
        this.pop(expression);
        DBSPExpression result = new DBSPVariablePath(expression.variable, type);
        this.map(expression, result);
        return VisitDecision.STOP;
    }

    /////////////////// statements

    @Override
    public VisitDecision preorder(DBSPExpressionStatement statement) {
        this.push(statement);
        DBSPExpression expression = this.transform(statement.expression);
        this.pop(statement);
        DBSPStatement result = new DBSPExpressionStatement(expression);
        this.map(statement, result);
        return VisitDecision.STOP;
    }

    public VisitDecision preorder(DBSPLetStatement statement) {
        this.push(statement);
        DBSPExpression init = this.transformN(statement.initializer);
        DBSPType type = this.transform(statement.type);
        this.pop(statement);
        DBSPStatement result;
        if (init != null)
            result = new DBSPLetStatement(statement.variable, init);
        else
            result = new DBSPLetStatement(statement.variable, type, statement.mutable);
        this.map(statement, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPComment comment) {
        this.map(comment, comment);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPConstItem item) {
        this.push(item);
        DBSPType type = this.transform(item.type);
        @Nullable DBSPExpression expression = this.transformN(item.expression);
        this.pop(item);
        DBSPConstItem result = new DBSPConstItem(item.name, type, expression);
        this.map(item, result);
        return VisitDecision.STOP;
    }

    /// Other objects

    @Override
    public VisitDecision preorder(DBSPParameter parameter) {
        this.push(parameter);
        DBSPType type = this.transform(parameter.type);
        this.pop(parameter);
        DBSPParameter result = new DBSPParameter(parameter.name, type);
        this.map(parameter, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPFunction function) {
        this.push(function);
        DBSPType returnType = this.transform(function.returnType);
        DBSPExpression body = this.transform(function.body);
        List<DBSPParameter> parameters =
                Linq.map(function.parameters, p -> this.apply(p).to(DBSPParameter.class));
        this.pop(function);
        DBSPFunction result = new DBSPFunction(
                function.name, parameters, returnType, body, function.annotations);
        this.map(function, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPAggregate.Implementation implementation) {
        this.push(implementation);
        DBSPExpression zero = this.transform(implementation.zero);
        DBSPExpression increment = this.transform(implementation.increment);
        @Nullable DBSPExpression postProcess = this.transformN(implementation.postProcess);
        DBSPExpression emptySetResult = this.transform(implementation.emptySetResult);
        DBSPType semiGroup = this.transform(implementation.semigroup);
        this.pop(implementation);

        DBSPAggregate.Implementation result = new DBSPAggregate.Implementation(implementation.operator, zero,
                    increment.to(DBSPClosureExpression.class),
                    postProcess != null ? postProcess.to(DBSPClosureExpression.class) : null,
                    emptySetResult, semiGroup);
        this.map(implementation, result);
        return VisitDecision.STOP;
    }

    @Override
    public VisitDecision preorder(DBSPAggregate aggregate) {
        this.push(aggregate);
        DBSPExpression rowVar = this.transform(aggregate.rowVar);
        DBSPAggregate.Implementation[] implementations =
                Linq.map(aggregate.components, c -> {
                            IDBSPInnerNode result = this.apply(c);
                            return result.to(DBSPAggregate.Implementation.class);
                        },
                        DBSPAggregate.Implementation.class);
        this.pop(aggregate);
        DBSPAggregate result = new DBSPAggregate(
                aggregate.getNode(), rowVar.to(DBSPVariablePath.class), implementations);
        this.map(aggregate, result);
        return VisitDecision.STOP;
    }

    /**
     * Given a visitor for inner nodes returns a visitor
     * that optimizes an entire circuit.
     */
    public CircuitRewriter circuitRewriter() {
        return new CircuitRewriter(this.errorReporter, this);
    }
}