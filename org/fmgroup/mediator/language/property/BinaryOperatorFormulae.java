package org.fmgroup.mediator.language.property;

import org.fmgroup.mediator.language.RawElement;
import org.fmgroup.mediator.language.ValidationException;
import org.fmgroup.mediator.language.property.PathFormulae.PathFormulae;
import org.fmgroup.mediator.language.term.Term;
import org.fmgroup.mediator.language.type.Type;

import java.util.Map;

/**
 * refer to notTemporalFormulae ...
 *
 * @param <T>
 */
public class BinaryOperatorFormulae<T extends Formulae> implements PathFormulae {
    private RawElement parent;
    private T left, right;
    private EnumBinaryOperatorTemporal opr;

    public T getLeft() {
        return left;
    }

    public BinaryOperatorFormulae setLeft(T left) {
        this.left = left;
        left.setParent(this);
        return this;
    }

    public T getRight() {
        return right;
    }

    public BinaryOperatorFormulae setRight(T right) {
        this.right = right;
        right.setParent(this);
        return this;
    }

    public EnumBinaryOperatorTemporal getOpr() {
        return opr;
    }

    public BinaryOperatorFormulae setOpr(EnumBinaryOperatorTemporal opr) {
        this.opr = opr;
        return this;
    }

    @Override
    public int getPrecedence() {
        return opr.oprLevel;
    }

    @Override
    public Term refactor(Map<String, Type> typeRewriteMap, Map<String, Term> termRewriteMap) throws ValidationException {
        return null;
    }

    @Override
    public RawElement getParent() {
        return this.parent;
    }

    @Override
    public BinaryOperatorFormulae<T> setParent(RawElement parent) {
        this.parent = parent;
        return this;
    }

    @Override
    public BinaryOperatorFormulae<T> copy(RawElement parent) throws ValidationException {
        BinaryOperatorFormulae<T> newFormulae = new BinaryOperatorFormulae<>();
        newFormulae.setParent(parent);
        newFormulae.setOpr(this.opr);
        if (this.left != null) {
            newFormulae.setLeft((T) this.left.copy(newFormulae));
        }
        if (this.right != null) {
            newFormulae.setRight((T) this.right.copy(newFormulae));
        }
        return newFormulae;
    }

    @Override
    public String toString() {
        return
                String.format(
                        right.getPrecedence() < this.getPrecedence() ? "%s %s (%s)" : "%s %s %s",
                        left.toString(), opr.toString(), right.toString()
                );
    }
}
