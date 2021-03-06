// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import com.cloudera.impala.catalog.Type;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.TreeNode;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Abstract base class for any statement that returns results
 * via a list of result expressions, for example a
 * SelectStmt or UnionStmt. Also maintains a map of expression substitutions
 * for replacing expressions from ORDER BY or GROUP BY clauses with
 * their corresponding result expressions.
 * Used for sharing members/methods and some of the analysis code, in particular the
 * analysis of the ORDER BY and LIMIT clauses.
 *
 */
public abstract class QueryStmt extends StatementBase {
  /////////////////////////////////////////
  // BEGIN: Members that need to be reset()

  protected WithClause withClause_;

  protected ArrayList<OrderByElement> orderByElements_;
  protected LimitElement limitElement_;

  // For a select statment:
  // original list of exprs in select clause (star-expanded, ordinals and
  // aliases substituted, agg output substituted)
  // For a union statement:
  // list of slotrefs into the tuple materialized by the union.
  protected ArrayList<Expr> resultExprs_ = Lists.newArrayList();

  // For a select statment: select list exprs resolved to base tbl refs
  // For a union statement: same as resultExprs
  protected ArrayList<Expr> baseTblResultExprs_ = Lists.newArrayList();

  /**
   * Map of expression substitutions for replacing aliases
   * in "order by" or "group by" clauses with their corresponding result expr.
   */
  protected final ExprSubstitutionMap aliasSmap_;

  /**
   * Select list item alias does not have to be unique.
   * This list contains all the non-unique aliases. For example,
   *   select int_col a, string_col a from alltypessmall;
   * Both columns are using the same alias "a".
   */
  protected final ArrayList<Expr> ambiguousAliasList_;

  protected SortInfo sortInfo_;

  // evaluateOrderBy_ is true if there is an order by clause that must be evaluated.
  // False for nested query stmts with an order-by clause without offset/limit.
  // sortInfo_ is still generated and used in analysis to ensure that the order-by clause
  // is well-formed.
  protected boolean evaluateOrderBy_;

  /////////////////////////////////////////
  // END: Members that need to be reset()

  QueryStmt(ArrayList<OrderByElement> orderByElements, LimitElement limitElement) {
    orderByElements_ = orderByElements;
    sortInfo_ = null;
    limitElement_ = limitElement == null ? new LimitElement(null, null) : limitElement;
    aliasSmap_ = new ExprSubstitutionMap();
    ambiguousAliasList_ = Lists.newArrayList();
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    if (isAnalyzed()) return;
    super.analyze(analyzer);
    analyzeLimit(analyzer);
    if (hasWithClause()) withClause_.analyze(analyzer);
  }

  /**
   * Returns a list containing all the materialized tuple ids that this stmt is
   * correlated with (i.e., those tuple ids from outer query blocks that TableRefs
   * inside this stmt are rooted at).
   *
   * Throws if this stmt contains an illegal mix of un/correlated table refs.
   * A statement is illegal if it contains a TableRef correlated with a parent query
   * block as well as a table ref with an absolute path (e.g. a BaseTabeRef). Such a
   * statement would generate a Subplan containing a base table scan (very expensive),
   * and should therefore be avoided.
   *
   * In other words, the following cases are legal:
   * (1) only uncorrelated table refs
   * (2) only correlated table refs
   * (3) a mix of correlated table refs and table refs rooted at those refs
   *     (the statement is 'self-contained' with respect to correlation)
   */
  public List<TupleId> getCorrelatedTupleIds(Analyzer analyzer)
      throws AnalysisException {
    // Correlated tuple ids of this stmt.
    List<TupleId> correlatedTupleIds = Lists.newArrayList();
    // First correlated and absolute table refs. Used for error detection/reporting.
    // We pick the first ones for simplicity. Choosing arbitrary ones is equally valid.
    TableRef correlatedRef = null;
    TableRef absoluteRef = null;
    // Materialized tuple ids of the table refs checked so far.
    Set<TupleId> tblRefIds = Sets.newHashSet();

    List<TableRef> tblRefs = Lists.newArrayList();
    collectTableRefs(tblRefs);
    for (TableRef tblRef: tblRefs) {
      if (absoluteRef == null && !tblRef.isRelative()) absoluteRef = tblRef;
      if (tblRef.isCorrelated()) {
        // Check if the correlated table ref is rooted at a tuple descriptor from within
        // this query stmt. If so, the correlation is contained within this stmt
        // and the table ref does not conflict with absolute refs.
        CollectionTableRef t = (CollectionTableRef) tblRef;
        Preconditions.checkNotNull(t.getResolvedPath().getRootDesc());
        // This check relies on tblRefs being in depth-first order.
        if (!tblRefIds.contains(t.getResolvedPath().getRootDesc().getId())) {
          if (correlatedRef == null) correlatedRef = tblRef;
          correlatedTupleIds.add(t.getResolvedPath().getRootDesc().getId());
        }
      }
      if (correlatedRef != null && absoluteRef != null) {
        throw new AnalysisException(String.format(
            "Nested query is illegal because it contains a table reference '%s' " +
            "correlated with an outer block as well as an uncorrelated one '%s':\n%s",
            correlatedRef.tableRefToSql(), absoluteRef.tableRefToSql(), toSql()));
      }
      tblRefIds.add(tblRef.getId());
    }
    return correlatedTupleIds;
  }

  private void analyzeLimit(Analyzer analyzer) throws AnalysisException {
    if (limitElement_.getOffsetExpr() != null && !hasOrderByClause()) {
      throw new AnalysisException("OFFSET requires an ORDER BY clause: " +
          limitElement_.toSql().trim());
    }
    limitElement_.analyze(analyzer);
  }

  /**
   * Creates sortInfo by resolving aliases and ordinals in the orderingExprs.
   * If the query stmt is an inline view/union operand, then order-by with no
   * limit with offset is not allowed, since that requires a sort and merging-exchange,
   * and subsequent query execution would occur on a single machine.
   * Sets evaluateOrderBy_ to false for ignored order-by w/o limit/offset in nested
   * queries.
   */
  protected void createSortInfo(Analyzer analyzer) throws AnalysisException {
    // not computing order by
    if (orderByElements_ == null) {
      evaluateOrderBy_ = false;
      return;
    }

    ArrayList<Expr> orderingExprs = Lists.newArrayList();
    ArrayList<Boolean> isAscOrder = Lists.newArrayList();
    ArrayList<Boolean> nullsFirstParams = Lists.newArrayList();

    // extract exprs
    for (OrderByElement orderByElement: orderByElements_) {
      if (orderByElement.getExpr().contains(Predicates.instanceOf(Subquery.class))) {
        throw new AnalysisException(
            "Subqueries are not supported in the ORDER BY clause.");
      }
      // create copies, we don't want to modify the original parse node, in case
      // we need to print it
      orderingExprs.add(orderByElement.getExpr().clone());
      isAscOrder.add(Boolean.valueOf(orderByElement.isAsc()));
      nullsFirstParams.add(orderByElement.getNullsFirstParam());
    }
    substituteOrdinalsAliases(orderingExprs, "ORDER BY", analyzer);

    if (!analyzer.isRootAnalyzer() && hasOffset() && !hasLimit()) {
      throw new AnalysisException("Order-by with offset without limit not supported" +
        " in nested queries.");
    }

    sortInfo_ = new SortInfo(orderingExprs, isAscOrder, nullsFirstParams);
    // order by w/o limit and offset in inline views, union operands and insert statements
    // are ignored.
    if (!hasLimit() && !hasOffset() && !analyzer.isRootAnalyzer()) {
      evaluateOrderBy_ = false;
      // Return a warning that the order by was ignored.
      StringBuilder strBuilder = new StringBuilder();
      strBuilder.append("Ignoring ORDER BY clause without LIMIT or OFFSET: ");
      strBuilder.append("ORDER BY ");
      strBuilder.append(orderByElements_.get(0).toSql());
      for (int i = 1; i < orderByElements_.size(); ++i) {
        strBuilder.append(", ").append(orderByElements_.get(i).toSql());
      }
      strBuilder.append(".\nAn ORDER BY appearing in a view, subquery, union operand, ");
      strBuilder.append("or an insert/ctas statement has no effect on the query result ");
      strBuilder.append("unless a LIMIT and/or OFFSET is used in conjunction ");
      strBuilder.append("with the ORDER BY.");
      analyzer.addWarning(strBuilder.toString());
    } else {
      evaluateOrderBy_ = true;
    }
  }

  /**
   * Create a tuple descriptor for the single tuple that is materialized, sorted and
   * output by the exec node implementing the sort. Done by materializing slot refs in
   * the order-by and result expressions. Those SlotRefs in the ordering and result exprs
   * are substituted with SlotRefs into the new tuple. This simplifies sorting logic for
   * total (no limit) sorts.
   * Done after analyzeAggregation() since ordering and result exprs may refer to
   * the outputs of aggregation. Invoked for UnionStmt as well since
   * TODO: We could do something more sophisticated than simply copying input
   * slotrefs - e.g. compute some order-by expressions.
   */
  protected void createSortTupleInfo(Analyzer analyzer) throws AnalysisException {
    Preconditions.checkState(evaluateOrderBy_);

    for (Expr orderingExpr: sortInfo_.getOrderingExprs()) {
      if (orderingExpr.getType().isComplexType()) {
        throw new AnalysisException(String.format("ORDER BY expression '%s' with " +
            "complex type '%s' is not supported.", orderingExpr.toSql(),
            orderingExpr.getType().toSql()));
      }
    }

    // sourceSlots contains the slots from the input row to materialize.
    Set<SlotRef> sourceSlots = Sets.newHashSet();
    TreeNode.collect(resultExprs_, Predicates.instanceOf(SlotRef.class), sourceSlots);
    TreeNode.collect(sortInfo_.getOrderingExprs(), Predicates.instanceOf(SlotRef.class),
        sourceSlots);

    TupleDescriptor sortTupleDesc = analyzer.getDescTbl().createTupleDescriptor("sort");
    List<Expr> sortTupleExprs = Lists.newArrayList();
    sortTupleDesc.setIsMaterialized(true);
    // substOrderBy is the mapping from slot refs in the input row to slot refs in the
    // materialized sort tuple.
    ExprSubstitutionMap substOrderBy = new ExprSubstitutionMap();
    for (SlotRef origSlotRef: sourceSlots) {
      SlotDescriptor origSlotDesc = origSlotRef.getDesc();
      SlotDescriptor materializedDesc =
          analyzer.copySlotDescriptor(origSlotDesc, sortTupleDesc);
      SlotRef cloneRef = new SlotRef(materializedDesc);
      substOrderBy.put(origSlotRef, cloneRef);
      analyzer.createAuxEquivPredicate(cloneRef, origSlotRef);
      sortTupleExprs.add(origSlotRef);
    }

    resultExprs_ = Expr.substituteList(resultExprs_, substOrderBy, analyzer, false);
    sortInfo_.substituteOrderingExprs(substOrderBy, analyzer);
    sortInfo_.setMaterializedTupleInfo(sortTupleDesc, sortTupleExprs);
  }

  /**
   * Return the first expr in exprs that is a non-unique alias. Return null if none of
   * exprs is an ambiguous alias.
   */
  protected Expr getFirstAmbiguousAlias(List<Expr> exprs) {
    for (Expr exp: exprs) {
      if (ambiguousAliasList_.contains(exp)) return exp;
    }
    return null;
  }

  /**
   * Substitute exprs of the form "<number>"  with the corresponding
   * expressions and any alias references in aliasSmap_.
   * Modifies exprs list in-place.
   */
  protected void substituteOrdinalsAliases(List<Expr> exprs, String errorPrefix,
      Analyzer analyzer) throws AnalysisException {
    Expr ambiguousAlias = getFirstAmbiguousAlias(exprs);
    if (ambiguousAlias != null) {
      throw new AnalysisException("Column '" + ambiguousAlias.toSql() +
          "' in " + errorPrefix + " clause is ambiguous");
    }

    ListIterator<Expr> i = exprs.listIterator();
    while (i.hasNext()) {
      Expr expr = i.next();
      // We can substitute either by ordinal or by alias.
      // If we substitute by ordinal, we should not replace any aliases, since
      // the new expression was copied from the select clause context, where
      // alias substitution is not performed in the same way.
      Expr substituteExpr = trySubstituteOrdinal(expr, errorPrefix, analyzer);
      if (substituteExpr == null) {
        substituteExpr = expr.trySubstitute(aliasSmap_, analyzer, false);
      }
      i.set(substituteExpr);
    }
  }

  // Attempt to replace an expression of form "<number>" with the corresponding
  // select list items.  Return null if not an ordinal expression.
  private Expr trySubstituteOrdinal(Expr expr, String errorPrefix,
      Analyzer analyzer) throws AnalysisException {
    if (!(expr instanceof NumericLiteral)) return null;
    expr.analyze(analyzer);
    if (!expr.getType().isIntegerType()) return null;
    long pos = ((NumericLiteral) expr).getLongValue();
    if (pos < 1) {
      throw new AnalysisException(
          errorPrefix + ": ordinal must be >= 1: " + expr.toSql());
    }
    if (pos > resultExprs_.size()) {
      throw new AnalysisException(
          errorPrefix + ": ordinal exceeds number of items in select list: "
          + expr.toSql());
    }

    // Create copy to protect against accidentally shared state.
    return resultExprs_.get((int) pos - 1).clone();
  }

  /**
   * UnionStmt and SelectStmt have different implementations.
   */
  public abstract ArrayList<String> getColLabels();

  /**
   * Returns the materialized tuple ids of the output of this stmt.
   * Used in case this stmt is part of an @InlineViewRef,
   * since we need to know the materialized tupls ids of a TableRef.
   * This call must be idempotent because it may be called more than once for Union stmt.
   * TODO: The name of this function has become outdated due to analytics
   * producing logical (non-materialized) tuples. Re-think and clean up.
   */
  public abstract void getMaterializedTupleIds(ArrayList<TupleId> tupleIdList);

  /**
   * Returns all physical (non-inline-view) TableRefs of this statement and the nested
   * statements of inline views. The returned TableRefs are in depth-first order.
   */
  public abstract void collectTableRefs(List<TableRef> tblRefs);

  public void setWithClause(WithClause withClause) { this.withClause_ = withClause; }
  public boolean hasWithClause() { return withClause_ != null; }
  public WithClause getWithClause() { return withClause_; }
  public boolean hasOrderByClause() { return orderByElements_ != null; }
  public boolean hasLimit() { return limitElement_.getLimitExpr() != null; }
  public long getLimit() { return limitElement_.getLimit(); }
  public boolean hasOffset() { return limitElement_.getOffsetExpr() != null; }
  public long getOffset() { return limitElement_.getOffset(); }
  public SortInfo getSortInfo() { return sortInfo_; }
  public boolean evaluateOrderBy() { return evaluateOrderBy_; }
  public ArrayList<Expr> getResultExprs() { return resultExprs_; }
  public ArrayList<Expr> getBaseTblResultExprs() { return baseTblResultExprs_; }
  public void setLimit(long limit) throws AnalysisException {
    Preconditions.checkState(limit >= 0);
    long newLimit = hasLimit() ? Math.min(limit, getLimit()) : limit;
    limitElement_ = new LimitElement(new NumericLiteral(Long.toString(newLimit),
        Type.BIGINT), null);
  }

  /**
   * Mark all slots that need to be materialized for the execution of this stmt.
   * This excludes slots referenced in resultExprs (it depends on the consumer of
   * the output of the stmt whether they'll be accessed) and single-table predicates
   * (the PlanNode that materializes that tuple can decide whether evaluating those
   * predicates requires slot materialization).
   * This is called prior to plan tree generation and allows tuple-materializing
   * PlanNodes to compute their tuple's mem layout.
   */
  public abstract void materializeRequiredSlots(Analyzer analyzer);

  /**
   * Mark slots referenced in exprs as materialized.
   */
  protected void materializeSlots(Analyzer analyzer, List<Expr> exprs) {
    List<SlotId> slotIds = Lists.newArrayList();
    for (Expr e: exprs) {
      e.getIds(null, slotIds);
    }
    analyzer.getDescTbl().markSlotsMaterialized(slotIds);
  }

  /**
   * Substitutes the result expressions with smap. Preserves the original types of
   * those expressions during the substitution.
   */
  public void substituteResultExprs(ExprSubstitutionMap smap, Analyzer analyzer) {
    resultExprs_ = Expr.substituteList(resultExprs_, smap, analyzer, true);
  }

  public ArrayList<OrderByElement> cloneOrderByElements() {
    if (orderByElements_ == null) return null;
    ArrayList<OrderByElement> result =
        Lists.newArrayListWithCapacity(orderByElements_.size());
    for (OrderByElement o: orderByElements_) result.add(o.clone());
    return result;
  }

  public WithClause cloneWithClause() {
    return withClause_ != null ? withClause_.clone() : null;
  }

  /**
   * C'tor for cloning.
   */
  protected QueryStmt(QueryStmt other) {
    super(other);
    withClause_ = other.cloneWithClause();
    orderByElements_ = other.cloneOrderByElements();
    limitElement_ = other.limitElement_.clone();
    resultExprs_ = Expr.cloneList(other.resultExprs_);
    baseTblResultExprs_ = Expr.cloneList(other.baseTblResultExprs_);
    aliasSmap_ = other.aliasSmap_.clone();
    ambiguousAliasList_ = Expr.cloneList(other.ambiguousAliasList_);
    sortInfo_ = (other.sortInfo_ != null) ? other.sortInfo_.clone() : null;
    analyzer_ = other.analyzer_;
    evaluateOrderBy_ = other.evaluateOrderBy_;
  }

  @Override
  public void reset() {
    super.reset();
    if (orderByElements_ != null) {
      for (OrderByElement o: orderByElements_) o.getExpr().reset();
    }
    limitElement_.reset();
    resultExprs_.clear();
    baseTblResultExprs_.clear();
    aliasSmap_.clear();
    ambiguousAliasList_.clear();
    sortInfo_ = null;
    evaluateOrderBy_ = false;
  }

  @Override
  public abstract QueryStmt clone();
}
