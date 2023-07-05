package it.unive.scsr.pentagons;

import it.unive.lisa.analysis.ScopeToken;
import it.unive.lisa.analysis.SemanticException;
import it.unive.lisa.analysis.numeric.Interval;
import it.unive.lisa.analysis.representation.DomainRepresentation;
import it.unive.lisa.analysis.representation.StringRepresentation;
import it.unive.lisa.analysis.value.ValueDomain;
import it.unive.lisa.program.cfg.ProgramPoint;
import it.unive.lisa.program.cfg.statement.comparison.GreaterOrEqual;
import it.unive.lisa.program.cfg.statement.comparison.GreaterThan;
import it.unive.lisa.program.cfg.statement.comparison.LessOrEqual;
import it.unive.lisa.program.cfg.statement.comparison.LessThan;
import it.unive.lisa.symbolic.SymbolicExpression;
import it.unive.lisa.symbolic.value.*;
import it.unive.lisa.symbolic.value.operator.LogicalOperator;
import it.unive.lisa.symbolic.value.operator.binary.*;
import it.unive.lisa.symbolic.value.operator.unary.LogicalNegation;
import it.unive.lisa.symbolic.value.operator.unary.NumericNegation;
import it.unive.lisa.util.datastructures.graph.algorithms.FixpointException;
import it.unive.lisa.util.numeric.MathNumber;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Pair;
import org.apache.commons.lang3.StringUtils;

import javax.swing.text.html.Option;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class PentagonDomain implements ValueDomain<PentagonDomain> {
    public enum PentagonType {
        BOTTOM,
        TOP,
        GENERAL
    }
    public static final PentagonDomain BOTTOM = new PentagonDomain(PentagonType.BOTTOM);
    public static final PentagonDomain TOP = new PentagonDomain(PentagonType.TOP);

    private static final Integer WIDENING_LIMIT = 1000;

    private final PentagonType type;
    private final Map<Identifier, PentagonElement> pentagons;

    public PentagonDomain() {
        this(PentagonType.GENERAL, new HashMap<>());
    }

    public PentagonDomain(PentagonType type) {
        this (type, new HashMap<>());
    }

    private PentagonDomain(PentagonType type, Map<Identifier, PentagonElement> pentagons){
        this.type = type;
        this.pentagons = pentagons;
    }


    @Override
    public PentagonDomain lub(PentagonDomain other) throws SemanticException {
        Set<Identifier> ids = new HashSet<>();
        ids.addAll(this.pentagons.keySet());
        ids.addAll(other.pentagons.keySet());

        PentagonDomain lub = new PentagonDomain();

        ids.forEach(id -> {
            PentagonElement element;
            if(!this.pentagons.containsKey(id)){
                element = other.pentagons.get(id);
            }
            else if(!other.pentagons.containsKey(id)){
                element = this.pentagons.get(id);
            } else {
                element = new PentagonElement(
                        getIntervalLub(other, id),
                        getSubLub(other, id)
                );
            }
            lub.pentagons.put(id, element);
        });

        return lub;
    }

    private Interval getIntervalLub(PentagonDomain other, Identifier id){
        Interval intvLub;

        Interval thisInterval = this.pentagons.get(id).getInterval();
        Interval otherInterval = other.pentagons.get(id).getInterval();

        if(thisInterval.isBottom())
            return otherInterval;
        if(otherInterval.isBottom())
            return thisInterval;

        if (thisInterval.isTop() || otherInterval.isTop()){
            intvLub = Interval.TOP;
        } else {
            intvLub = new Interval(
                    thisInterval.interval.getLow().min(otherInterval.interval.getLow()),
                    thisInterval.interval.getHigh().max(otherInterval.interval.getHigh())
            );
        }
        return intvLub;
    }

    private Set<Identifier> getSubLub(PentagonDomain other, Identifier id){
        Set<Identifier> thisSub = this.pentagons.get(id).getSub();
        Set<Identifier> otherSub = other.pentagons.get(id).getSub();

        Set<Identifier> out = new HashSet<>(thisSub);
        out.retainAll(otherSub);
        return out;
    }

    @Override
    public boolean lessOrEqual(PentagonDomain that) {
        return isIntervalLessOrEqual(getBoxDomain(this), getBoxDomain(that)) &&
                isSubLessOrEqual(getBoxDomain(this), getSubDomain(this), getSubDomain(that));
    }

    private Map<Identifier, Interval> getBoxDomain(PentagonDomain p){
        return p.pentagons.keySet().stream()
                .map(id -> new Pair<>(id, p.pentagons.get(id).getInterval()))
                .collect(Collectors.toMap(pair -> pair.a, pair -> pair.b));
    }

    private Map<Identifier, Set<Identifier>> getSubDomain(PentagonDomain p){
        return p.pentagons.keySet().stream()
                .map(id -> new Pair<>(id, p.pentagons.get(id).getSub()))
                .collect(Collectors.toMap(pair -> pair.a, pair -> pair.b));
    }

    private boolean isIntervalLessOrEqual(Map<Identifier, Interval> b1, Map<Identifier, Interval> b2) {
        return b1.entrySet().stream().allMatch(x -> {
            try {
                return b2.containsKey(x.getKey()) && x.getValue().lessOrEqual(b2.get(x.getKey()));
            } catch (SemanticException e) {
                return false;
            }
        });
    }

    private boolean isSubLessOrEqual(Map<Identifier, Interval> b1, Map<Identifier, Set<Identifier>> s1, Map<Identifier, Set<Identifier>> s2) {
        return s2.keySet().stream().allMatch(x -> s2.get(x).stream()
                .allMatch(y -> s1.get(x).contains(y) ||
                        b1.get(x).interval.getHigh().compareTo(b1.get(y).interval.getLow()) < 0)
        );
    }

    @Override
    public PentagonDomain top() {
        return TOP;
    }

    @Override
    public PentagonDomain bottom() {
        return BOTTOM;
    }

    @Override
    public PentagonDomain assign(Identifier id, ValueExpression expression, ProgramPoint pp) throws SemanticException {
        PentagonDomain freshPentagon = this.copy();
        freshPentagon.removeElement(id);

        Set<Identifier> freshSub = new HashSet<>();
        Optional<Interval> freshInterval = Optional.empty();

        if (expression instanceof Constant && (expression.getStaticType().isNumericType() && expression.getStaticType().asNumericType().isIntegral()) ||
                expression instanceof Identifier) {
            PentagonElement right = freshPentagon.retrievePentagonElement(expression).orElseThrow(SemanticException::new);
            freshInterval = Optional.of(right.getInterval());
            freshSub = right.getSub();
        }
        else if (expression instanceof UnaryExpression && ((UnaryExpression) expression).getOperator() instanceof NumericNegation) {
            PentagonElement beforeNegation = freshPentagon.retrievePentagonElement(((UnaryExpression) expression).getExpression()).orElseThrow(SemanticException::new);
            freshInterval = Optional.of(new Interval(
                    beforeNegation.getIntervalHigh().multiply(new MathNumber(-1)),
                    beforeNegation.getIntervalLow().multiply(new MathNumber(-1))
            ));
        }
        else if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            PentagonElement left = freshPentagon.retrievePentagonElement(binaryExpression.getLeft()).orElseThrow(SemanticException::new);
            PentagonElement right = freshPentagon.retrievePentagonElement(binaryExpression.getRight()).orElseThrow(SemanticException::new);

            freshInterval = Optional.of(
                    left.getInterval().isBottom() ? Interval.BOTTOM : left.getInterval().evalBinaryExpression(
                            ((BinaryExpression) expression).getOperator(), left.getInterval(), right.getInterval(), pp
                    )
            );

        }
        if (freshInterval.isPresent()){
            Set<Identifier> finalFreshSub = freshSub;
            Interval finalFreshInterval = freshInterval.get();

            freshPentagon.pentagons.forEach(((identifier, element) -> {
                if (!finalFreshInterval.isBottom() && !element.getInterval().isBottom() && finalFreshInterval.interval.getHigh().compareTo(element.getIntervalLow()) < 0) {
                    finalFreshSub.add(identifier);
                    finalFreshSub.addAll(element.getSub());
                } else if (!finalFreshInterval.isBottom() && !element.getInterval().isBottom() && finalFreshInterval.interval.getLow().compareTo(element.getIntervalHigh()) > 0) {
                    element.getSub().add(id);
                    element.getSub().addAll(finalFreshSub);
                }
            }));

            freshPentagon.addElement(id, new PentagonElement(freshInterval.get(), freshSub));
        }


        System.out.println("Assign: " + freshPentagon.pentagons);
        return freshPentagon;
    }

    private Optional<PentagonElement> retrievePentagonElement(SymbolicExpression expression) {
        if (expression instanceof Identifier) {
            Identifier identifier = (Identifier) expression;
            pentagons.putIfAbsent(identifier, PentagonElement.TOP);
            return Optional.of(pentagons.get(identifier));
        } else if (expression instanceof Constant) {
            Constant constant = (Constant) expression;
            return Optional.of(new PentagonElement(
                    new Interval((Integer) constant.getValue(), (Integer) constant.getValue()),
                    new HashSet<>()));
        }
        return Optional.empty();
    }

    /**
     * Removes the identifier from the pentagon
     * @param id identifier to remove
     */
    private PentagonElement removeElement(Identifier id) {
        this.pentagons.values().
                forEach(pentagonElement -> pentagonElement.getSub().removeIf(identifier -> identifier.equals(id)));
        return this.pentagons.remove(id);
    }

    /**
     * Adds a new Identifier to the pentagon
     * @param id the identifier
     * @param element the corresponding PentagonElement
     */
    private void addElement(Identifier id, PentagonElement element) {
        this.pentagons.put(id, element);
    }

    @Override
    public PentagonDomain smallStepSemantics(ValueExpression expression, ProgramPoint pp) throws SemanticException {
        return this.copy();
    }

    private PentagonDomain negateLogicalBinaryExpression(BinaryExpression expression) throws SemanticException{
        PentagonElement left = retrievePentagonElement(expression.getLeft()).orElseGet(() -> PentagonElement.TOP);
        PentagonElement right = retrievePentagonElement(expression.getRight()).orElseGet(() -> PentagonElement.TOP);
        Optional<Identifier> leftIdentifier = expression.getLeft() instanceof Identifier ? Optional.of((Identifier) expression.getLeft()) : Optional.empty();
        Optional<Identifier> rightIdentifier = expression.getRight() instanceof Identifier ? Optional.of((Identifier) expression.getRight()) : Optional.empty();

        // leftIdentifier.ifPresent(this::removeElement);
        // rightIdentifier.ifPresent(this::removeElement);

        System.out.print("(Negated) ");

        if (expression.getOperator() instanceof ComparisonLt){ // !(left < right) -> left >= right -> right <= left
            return compareLE(right, left, rightIdentifier, leftIdentifier);
        }
        if (expression.getOperator() instanceof ComparisonGt){ // !(left > right) -> left <= right
            return compareLE(left, right, leftIdentifier, rightIdentifier);
        }
        if (expression.getOperator() instanceof ComparisonLe){ // !(left <= right) -> left > right -> right < left
            return compareLT(right, left, rightIdentifier, leftIdentifier);
        }
        if (expression.getOperator() instanceof ComparisonGe){ // !(left >= right) -> left < right
            return compareLT(left, right, leftIdentifier, rightIdentifier);
        }
        if (expression.getOperator() instanceof ComparisonNe){ // !(left >= right) -> left < right
            return compareEQ(left, right, leftIdentifier, rightIdentifier);
        }
        if (expression.getOperator() instanceof ComparisonEq){
            return this;
        }

        System.out.println("WHAT!!");

        return this.copy();
    }

    private PentagonDomain compareLE(PentagonElement left, PentagonElement right, Optional<Identifier> leftIdentifier, Optional<Identifier> rightIdentifier){
        Interval leftInterval, rightInterval;
        // update left interval as described in the javadoc
        try {
            leftInterval = new Interval(
                    left.getIntervalLow(),
                    left.getIntervalHigh().min(right.getIntervalHigh()));
        } catch (IllegalArgumentException exception){
            leftInterval = Interval.BOTTOM;
        }

        // update right interval as described in the javadoc
        try {
            rightInterval = new Interval(
                    right.getIntervalLow().max(left.getIntervalLow()),
                    right.getIntervalHigh());
        } catch (IllegalArgumentException exception) {
            rightInterval = Interval.BOTTOM;
        }

        // widening
        if(!leftInterval.isBottom() && leftInterval.interval.getHigh().compareTo(new MathNumber(WIDENING_LIMIT)) > 0)
            leftInterval = new Interval(leftInterval.interval.getLow(), MathNumber.PLUS_INFINITY);
        if(!rightInterval.isBottom() && rightInterval.interval.getHigh().compareTo(new MathNumber(WIDENING_LIMIT)) > 0)
            rightInterval = new Interval(rightInterval.interval.getLow(), MathNumber.PLUS_INFINITY);
        if(!leftInterval.isBottom() && leftInterval.interval.getLow().compareTo(new MathNumber(-WIDENING_LIMIT)) < 0)
            leftInterval = new Interval(MathNumber.MINUS_INFINITY, leftInterval.interval.getHigh());
        if(!rightInterval.isBottom() && rightInterval.interval.getLow().compareTo(new MathNumber(-WIDENING_LIMIT)) < 0)
            rightInterval = new Interval(MathNumber.MINUS_INFINITY, rightInterval.interval.getHigh());

        // update left set as described in the javadoc
        left.getSub().addAll(right.getSub()); // if right is a constant .getSub() => {}
        rightIdentifier.ifPresent(idr -> leftIdentifier.ifPresent(idl -> pentagons.get(idr).getSub().remove(idl)));

        // update the pentagon domain with the computed data
        Interval finalLeftInterval = leftInterval;
        Interval finalRightInterval = rightInterval;
        leftIdentifier.ifPresent(id -> pentagons.put(id, new PentagonElement(finalLeftInterval, left.getSub())));
        rightIdentifier.ifPresent(id -> pentagons.put(id, new PentagonElement(finalRightInterval, right.getSub())));

        System.out.println("CompareLE " + this.pentagons);
        return this;
    }

    /**
     * left -> (left_low, min(left_high, right_high-1))<br>
     * right -> (max(right_low, left_low+1), right_high)<br>
     * sub(left) -> sub(left) U sub(right) U {right} <br>
     * */
    private PentagonDomain compareLT(PentagonElement left, PentagonElement right, Optional<Identifier> leftIdentifier, Optional<Identifier> rightIdentifier) {
        Interval leftInterval, rightInterval;
        // update left interval as described in the javadoc
        try {
            leftInterval = new Interval(
                    left.getIntervalLow(),
                    left.getIntervalHigh().min(right.getIntervalHigh().add(new MathNumber(-1))));
        } catch (IllegalArgumentException exception){
            leftInterval = Interval.BOTTOM;
        }

        // update right interval as described in the javadoc
        try {
            rightInterval = new Interval(
                    right.getIntervalLow().max(left.getIntervalLow().add(new MathNumber(1))),
                    right.getIntervalHigh());
        } catch (IllegalArgumentException exception) {
            rightInterval = Interval.BOTTOM;
        }

        // widening
        if(!leftInterval.isBottom() && leftInterval.interval.getHigh().compareTo(new MathNumber(WIDENING_LIMIT)) > 0)
            leftInterval = new Interval(leftInterval.interval.getLow(), MathNumber.PLUS_INFINITY);
        if(!rightInterval.isBottom() && rightInterval.interval.getHigh().compareTo(new MathNumber(WIDENING_LIMIT)) > 0)
            rightInterval = new Interval(rightInterval.interval.getLow(), MathNumber.PLUS_INFINITY);
        if(!leftInterval.isBottom() && leftInterval.interval.getLow().compareTo(new MathNumber(-WIDENING_LIMIT)) < 0)
            leftInterval = new Interval(MathNumber.MINUS_INFINITY, leftInterval.interval.getHigh());
        if(!rightInterval.isBottom() && rightInterval.interval.getLow().compareTo(new MathNumber(-WIDENING_LIMIT)) < 0)
            rightInterval = new Interval(MathNumber.MINUS_INFINITY, rightInterval.interval.getHigh());

        // update left set as described in the javadoc
        left.getSub().addAll(right.getSub()); // if right is a constant .getSub() => {}
        rightIdentifier.ifPresent(id -> left.getSub().add(id)); // add the right identifier if not a constant
        rightIdentifier.flatMap(idr -> leftIdentifier).ifPresent(idl -> right.getSub().remove(idl));

        // update the pentagon domain with the computed data
        Interval finalLeftInterval = leftInterval;
        Interval finalRightInterval = rightInterval;
        leftIdentifier.ifPresent(id -> pentagons.put(id, new PentagonElement(finalLeftInterval, left.getSub())));
        rightIdentifier.ifPresent(id -> pentagons.put(id, new PentagonElement(finalRightInterval, right.getSub())));

        System.out.println("CompareLT: " + this.pentagons);

        return this;
    }

    private PentagonDomain compareEQ(PentagonElement left, PentagonElement right, Optional<Identifier> leftIdentifier, Optional<Identifier> rightIdentifier) {
        Interval leftInterval, rightInterval;
        // update left interval as described in the javadoc
        try {
            leftInterval = new Interval(
                    left.getIntervalLow().max(right.getIntervalLow()),
                    left.getIntervalHigh().min(right.getIntervalHigh())
            );
        } catch (IllegalArgumentException exception){
            leftInterval = Interval.BOTTOM;
        }

        // update right interval as described in the javadoc
        try {
            rightInterval = new Interval(
                    left.getIntervalLow().max(right.getIntervalLow()),
                    left.getIntervalHigh().min(right.getIntervalHigh())
            );
        } catch (IllegalArgumentException exception) {
            rightInterval = Interval.BOTTOM;
        }

        // widening
        if(!leftInterval.isBottom() && leftInterval.interval.getHigh().compareTo(new MathNumber(WIDENING_LIMIT)) > 0)
            leftInterval = new Interval(leftInterval.interval.getLow(), MathNumber.PLUS_INFINITY);
        if(!rightInterval.isBottom() && rightInterval.interval.getHigh().compareTo(new MathNumber(WIDENING_LIMIT)) > 0)
            rightInterval = new Interval(rightInterval.interval.getLow(), MathNumber.PLUS_INFINITY);
        if(!leftInterval.isBottom() && leftInterval.interval.getLow().compareTo(new MathNumber(-WIDENING_LIMIT)) < 0)
            leftInterval = new Interval(MathNumber.MINUS_INFINITY, leftInterval.interval.getHigh());
        if(!rightInterval.isBottom() && rightInterval.interval.getLow().compareTo(new MathNumber(-WIDENING_LIMIT)) < 0)
            rightInterval = new Interval(MathNumber.MINUS_INFINITY, rightInterval.interval.getHigh());

        // update both sets as described in the javadoc
        leftIdentifier.ifPresent(id -> left.getSub().addAll(right.getSub()));
        rightIdentifier.ifPresent(id -> right.getSub().addAll(left.getSub()));
        leftIdentifier.ifPresent(id -> right.getSub().remove(id));
        rightIdentifier.ifPresent(id -> left.getSub().remove(id));

        // update the pentagon domain with the computed data
        Interval finalLeftInterval = leftInterval;
        Interval finalRightInterval = rightInterval;
        leftIdentifier.ifPresent(id -> pentagons.put(id, new PentagonElement(finalLeftInterval, left.getSub())));
        rightIdentifier.ifPresent(id -> pentagons.put(id, new PentagonElement(finalRightInterval, right.getSub())));

        return this;
    }


    @Override
    public PentagonDomain assume(ValueExpression expression, ProgramPoint pp) throws SemanticException {
        PentagonDomain freshPentagon = this.copy();

        // TODO: comparison sub-methods need to be void
        if(expression instanceof UnaryExpression){
            UnaryExpression unaryExpression = (UnaryExpression) expression;
            System.out.println("Assume: " + unaryExpression);

            if (unaryExpression.getOperator() instanceof LogicalNegation){
                return freshPentagon.negateLogicalBinaryExpression((BinaryExpression) unaryExpression.getExpression());
            }

            return freshPentagon;
        }
        if(expression instanceof BinaryExpression){
            BinaryExpression exp = (BinaryExpression) expression;
            System.out.println("Assume: " + exp);

            PentagonElement left = freshPentagon.retrievePentagonElement(exp.getLeft()).orElseGet(() -> PentagonElement.TOP);
            PentagonElement right = freshPentagon.retrievePentagonElement(exp.getRight()).orElseGet(() -> PentagonElement.TOP);
            Optional<Identifier> leftIdentifier = exp.getLeft() instanceof Identifier ? Optional.of((Identifier) exp.getLeft()) : Optional.empty();
            Optional<Identifier> rightIdentifier = exp.getRight() instanceof Identifier ? Optional.of((Identifier) exp.getRight()) : Optional.empty();

            if (exp.getOperator() instanceof ComparisonLt){
                return freshPentagon.compareLT(left, right, leftIdentifier, rightIdentifier);
            }
            if (exp.getOperator() instanceof ComparisonGt){ // left > right -> right < left
                return freshPentagon.compareLT(right, left, rightIdentifier, leftIdentifier);
            }
            if (exp.getOperator() instanceof ComparisonLe){
                return freshPentagon.compareLE(left, right, leftIdentifier, rightIdentifier);
            }
            if (exp.getOperator() instanceof ComparisonGe){ // left >= right -> right <= left
                return freshPentagon.compareLE(right, left, rightIdentifier, leftIdentifier);
            }
            if (exp.getOperator() instanceof ComparisonEq){
                return freshPentagon.compareEQ(left, right, leftIdentifier, rightIdentifier);
            }
        }

        return freshPentagon;
    }

    @Override
    public PentagonDomain forgetIdentifier(Identifier id) throws SemanticException {
        return this;
    }

    @Override
    public PentagonDomain forgetIdentifiersIf(Predicate<Identifier> test) throws SemanticException {
        return this;
    }

    @Override
    public Satisfiability satisfies(ValueExpression expression, ProgramPoint pp) throws SemanticException {
        System.out.println("Satisfies: " + expression);

        if (expression instanceof BinaryExpression){
            BinaryExpression exp = (BinaryExpression) expression;

            PentagonElement left = retrievePentagonElement(exp.getLeft()).orElseGet(() -> PentagonElement.TOP);
            PentagonElement right = retrievePentagonElement(exp.getRight()).orElseGet(() -> PentagonElement.TOP);
            Optional<Identifier> leftIdentifier = exp.getLeft() instanceof Identifier ? Optional.of((Identifier) exp.getLeft()) : Optional.empty();
            Optional<Identifier> rightIdentifier = exp.getRight() instanceof Identifier ? Optional.of((Identifier) exp.getRight()) : Optional.empty();

            if (exp.getOperator() instanceof ComparisonLt){
                return satisfiesLT(left, right, leftIdentifier, rightIdentifier);
            } else if (exp.getOperator() instanceof ComparisonGt){
                return satisfiesLT(right, left, rightIdentifier, leftIdentifier);
            } else if (exp.getOperator() instanceof ComparisonEq){
                return satisfiesEQ(left, right, leftIdentifier, rightIdentifier);
            } else if (exp.getOperator() instanceof ComparisonLe){
                return satisfiesLE(left, right, leftIdentifier, rightIdentifier);
            } else if (exp.getOperator() instanceof ComparisonGe){
                return satisfiesLE(right, left, rightIdentifier, leftIdentifier);
            } else if (exp.getOperator() instanceof ComparisonNe){
                return satisfiesNE(left, right, leftIdentifier, rightIdentifier);
            }
        }

        return Satisfiability.UNKNOWN;
    }

    public Satisfiability satisfiesLT(PentagonElement left, PentagonElement right, Optional<Identifier> leftIdentifier, Optional<Identifier> rightIdentifier){
        if (rightIdentifier.isPresent() && left.getSub().contains(rightIdentifier.get()) ||
                left.getIntervalHigh().compareTo(right.getIntervalLow()) < 0){
            return Satisfiability.SATISFIED;
        }

        if (leftIdentifier.isPresent() && right.getSub().contains(leftIdentifier.get()) ||
                right.getIntervalHigh().compareTo(left.getIntervalLow()) <= 0){
            return Satisfiability.NOT_SATISFIED;
        }

        return Satisfiability.UNKNOWN;
    }

    public Satisfiability satisfiesEQ(PentagonElement left, PentagonElement right, Optional<Identifier> leftIdentifier, Optional<Identifier> rightIdentifier){
        if (rightIdentifier.isPresent() && left.getSub().contains(rightIdentifier.get()) ||
                leftIdentifier.isPresent() && right.getSub().contains(leftIdentifier.get()) ||
                left.getIntervalHigh().compareTo(right.getIntervalLow()) < 0 ||
                right.getIntervalHigh().compareTo(left.getIntervalLow()) < 0){
            return Satisfiability.NOT_SATISFIED;
        }

        return Satisfiability.UNKNOWN;
    }

    public Satisfiability satisfiesLE(PentagonElement left, PentagonElement right, Optional<Identifier> leftIdentifier, Optional<Identifier> rightIdentifier){
        if (rightIdentifier.isPresent() && left.getSub().contains(rightIdentifier.get()) ||
                left.getIntervalHigh().compareTo(right.getIntervalLow()) <= 0) {
            return Satisfiability.SATISFIED;
        }

        if (right.getIntervalHigh().compareTo(left.getIntervalLow()) < 0) {
            return Satisfiability.NOT_SATISFIED;
        }

        return Satisfiability.UNKNOWN;
    }

    public Satisfiability satisfiesNE(PentagonElement left, PentagonElement right, Optional<Identifier> leftIdentifier, Optional<Identifier> rightIdentifier){
        if (rightIdentifier.isPresent() && left.getSub().contains(rightIdentifier.get()) ||
                leftIdentifier.isPresent() && right.getSub().contains(leftIdentifier.get()) ||
                left.getIntervalHigh().compareTo(right.getIntervalLow()) < 0 ||
                right.getIntervalHigh().compareTo(left.getIntervalLow()) < 0) {
            return Satisfiability.SATISFIED;
        }

        return Satisfiability.UNKNOWN;
    }

    @Override
    public PentagonDomain pushScope(ScopeToken token) throws SemanticException {
        return this.copy();
    }

    @Override
    public PentagonDomain popScope(ScopeToken token) throws SemanticException {
        return this.copy();
    }

    @Override
    public DomainRepresentation representation() {
        return new StringRepresentation(StringUtils.join(
                pentagons.entrySet().stream().map(e -> e.getKey().getName() + " -> " + e.getValue().representation().toString()).collect(Collectors.toList()),
                "\n <br>"));
    }

    @Override
    public int hashCode() {
        return this.pentagons.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PentagonDomain)) return false;
        return this.type == ((PentagonDomain) obj).type && this.pentagons.equals(((PentagonDomain) obj).pentagons);
    }

    /**
     * Performs a deep copy of the current instance
     * @return the copy of this
     */
    private PentagonDomain copy() {
        PentagonDomain newDomain = new PentagonDomain();
        pentagons.forEach((identifier, pentagonElement) ->
                newDomain.pentagons.put(identifier, new PentagonElement(
                        pentagonElement.getInterval().isBottom() ? Interval.BOTTOM : new Interval(pentagonElement.getIntervalLow(), pentagonElement.getIntervalHigh()),
                        new HashSet<>(pentagonElement.getSub())
                        )
                )
        );
        return newDomain;
    }
}
