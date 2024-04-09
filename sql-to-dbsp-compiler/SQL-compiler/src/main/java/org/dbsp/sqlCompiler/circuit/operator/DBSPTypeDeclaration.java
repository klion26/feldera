package org.dbsp.sqlCompiler.circuit.operator;

import org.dbsp.sqlCompiler.compiler.visitors.VisitDecision;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitVisitor;
import org.dbsp.sqlCompiler.ir.DBSPNode;
import org.dbsp.sqlCompiler.ir.IDBSPOuterNode;
import org.dbsp.sqlCompiler.ir.statement.DBSPItem;
import org.dbsp.util.IIndentStream;

/** Wraps a type declaration into an OuterNode */
public class DBSPTypeDeclaration extends DBSPNode implements IDBSPOuterNode {
    public final DBSPItem item;

    public DBSPTypeDeclaration(DBSPItem item) {
        super(item.getNode());
        this.item = item;
    }

    @Override
    public void accept(CircuitVisitor visitor) {
        visitor.push(this);
        VisitDecision decision = visitor.preorder(this);
        if (!decision.stop())
            visitor.postorder(this);
        visitor.pop(this);
    }

    @Override
    public IIndentStream toString(IIndentStream builder) {
        return this.item.toString(builder);
    }
}