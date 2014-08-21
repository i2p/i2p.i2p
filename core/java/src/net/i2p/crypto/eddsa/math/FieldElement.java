package net.i2p.crypto.eddsa.math;

public abstract class FieldElement {
    protected final Field f;

    public FieldElement(Field f) {
        this.f = f;
    }

    /**
     * Encode a FieldElement in its (b-1)-bit encoding.
     * @return the (b-1)-bit encoding of this FieldElement.
     */
    public byte[] toByteArray() {
        return f.getEncoding().encode(this);
    }

    public abstract boolean isNonZero();

    public boolean isNegative() {
        return f.getEncoding().isNegative(this);
    }

    public abstract FieldElement add(FieldElement val);

    public FieldElement addOne() {
        return add(f.one);
    }

    public abstract FieldElement subtract(FieldElement val);

    public FieldElement subtractOne() {
        return subtract(f.one);
    }

    public abstract FieldElement negate();

    public FieldElement divide(FieldElement val) {
        return multiply(val.invert());
    }

    public abstract FieldElement multiply(FieldElement val);

    public abstract FieldElement square();

    public abstract FieldElement squareAndDouble();

    public abstract FieldElement invert();

    public abstract FieldElement pow22523();
}
