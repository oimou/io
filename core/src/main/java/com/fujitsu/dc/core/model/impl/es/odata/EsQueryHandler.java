/**
 * personium.io
 * Copyright 2014 FUJITSU LIMITED
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fujitsu.dc.core.model.impl.es.odata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.odata4j.edm.EdmEntityType;
import org.odata4j.edm.EdmProperty;
import org.odata4j.edm.EdmSimpleType;
import org.odata4j.expression.AddExpression;
import org.odata4j.expression.AggregateAllFunction;
import org.odata4j.expression.AggregateAnyFunction;
import org.odata4j.expression.AndExpression;
import org.odata4j.expression.BinaryLiteral;
import org.odata4j.expression.BoolParenExpression;
import org.odata4j.expression.BooleanLiteral;
import org.odata4j.expression.ByteLiteral;
import org.odata4j.expression.CastExpression;
import org.odata4j.expression.CeilingMethodCallExpression;
import org.odata4j.expression.CommonExpression;
import org.odata4j.expression.ConcatMethodCallExpression;
import org.odata4j.expression.DateTimeLiteral;
import org.odata4j.expression.DateTimeOffsetLiteral;
import org.odata4j.expression.DayMethodCallExpression;
import org.odata4j.expression.DecimalLiteral;
import org.odata4j.expression.DivExpression;
import org.odata4j.expression.DoubleLiteral;
import org.odata4j.expression.EndsWithMethodCallExpression;
import org.odata4j.expression.EntitySimpleProperty;
import org.odata4j.expression.EqExpression;
import org.odata4j.expression.ExpressionVisitor;
import org.odata4j.expression.FloorMethodCallExpression;
import org.odata4j.expression.GeExpression;
import org.odata4j.expression.GtExpression;
import org.odata4j.expression.GuidLiteral;
import org.odata4j.expression.HourMethodCallExpression;
import org.odata4j.expression.IndexOfMethodCallExpression;
import org.odata4j.expression.Int64Literal;
import org.odata4j.expression.IntegralLiteral;
import org.odata4j.expression.IsofExpression;
import org.odata4j.expression.LeExpression;
import org.odata4j.expression.LengthMethodCallExpression;
import org.odata4j.expression.LtExpression;
import org.odata4j.expression.MinuteMethodCallExpression;
import org.odata4j.expression.ModExpression;
import org.odata4j.expression.MonthMethodCallExpression;
import org.odata4j.expression.MulExpression;
import org.odata4j.expression.NeExpression;
import org.odata4j.expression.NegateExpression;
import org.odata4j.expression.NotExpression;
import org.odata4j.expression.NullLiteral;
import org.odata4j.expression.OrExpression;
import org.odata4j.expression.OrderByExpression;
import org.odata4j.expression.OrderByExpression.Direction;
import org.odata4j.expression.ParenExpression;
import org.odata4j.expression.ReplaceMethodCallExpression;
import org.odata4j.expression.RoundMethodCallExpression;
import org.odata4j.expression.SByteLiteral;
import org.odata4j.expression.SecondMethodCallExpression;
import org.odata4j.expression.SingleLiteral;
import org.odata4j.expression.StartsWithMethodCallExpression;
import org.odata4j.expression.StringLiteral;
import org.odata4j.expression.SubExpression;
import org.odata4j.expression.SubstringMethodCallExpression;
import org.odata4j.expression.SubstringOfMethodCallExpression;
import org.odata4j.expression.TimeLiteral;
import org.odata4j.expression.ToLowerMethodCallExpression;
import org.odata4j.expression.ToUpperMethodCallExpression;
import org.odata4j.expression.TrimMethodCallExpression;
import org.odata4j.expression.YearMethodCallExpression;
import org.odata4j.producer.QueryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fujitsu.dc.core.DcCoreConfig;
import com.fujitsu.dc.core.DcCoreException;
import com.fujitsu.dc.core.model.ctl.Common;
import com.fujitsu.dc.core.model.impl.es.QueryMapFactory;
import com.fujitsu.dc.core.model.impl.es.doc.OEntityDocHandler;

/**
 * ODataの$filterをはじめとするクエリをESのJSONベースQueryDSLに変換する.
 * $filterはOata4JではBoolCommonExpressionに変換される。
 * OData4JではExpressionの評価にはVisitorパターンを採用しており、
 * 本クラスはここでVisitorとして振る舞うべくExpressionVisitorを実装している。
 * ひと通りVisitを終えたのち、本オブジェクトにgetSource()すると、
 * ESのSearchRequestに渡すべきJSONが取得できる。
 */
public class EsQueryHandler implements ExpressionVisitor, ODataQueryHandler {
    private static final int DEFAULT_TOP_VALUE = DcCoreConfig.getTopQueryDefaultSize();
    EdmEntityType entityType;
    Map<String, Object> source;
    Map<String, Object> current;
    Stack<Map<String, Object>> stack = new Stack<Map<String, Object>>();
    Map<String, Object> orderBy;
    /**
     * SORT_ASC 昇順.
     */
    public static final String SORT_ASC = "asc";
    /**
     * SORT_DESC 降順.
     */
    public static final String SORT_DESC = "desc";

    /**
     * ログ.
     */
    static Logger log = LoggerFactory.getLogger(EsQueryHandler.class);

    /**
     * コンストラクタ.
     */
    public EsQueryHandler() {
        this.source = new HashMap<String, Object>();
        this.stack.push(this.source);
        this.current = new HashMap<String, Object>();
        this.source.put("filter", this.current);
    }

    /**
     * コンストラクタ2.
     * $filter, $skip, $top, $orderby, $select を処理する。
     * $expand は対応していない。
     * @param entityType エンティティタイプ
     */
    public EsQueryHandler(EdmEntityType entityType) {
        this.source = new HashMap<String, Object>();
        this.entityType = entityType;
    }

    /**
     * 初期化.
     * @param queryInfo OData4jのQueryInfo.
     * @param implicitConds 暗黙検索条件.
     */
    public void initialize(QueryInfo queryInfo, List<Map<String, Object>> implicitConds) {
        List<Map<String, Object>> filters = new ArrayList<Map<String, Object>>();
        if (queryInfo != null) {
            if (queryInfo.filter != null) {
                this.stack.push(this.source);
                this.current = new HashMap<String, Object>();
                filters.add(this.current);
                queryInfo.filter.visit(this);
            }

            if (queryInfo.customOptions != null && !queryInfo.customOptions.isEmpty()) {
                String keywords = queryInfo.customOptions.get("q");
                if (keywords != null && !keywords.equals("")) {
                    // 半角空白が指定された場合は、AND検索とする
                    for (String keyword : keywords.split(" ")) {
                        if (keyword.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> map = new HashMap<String, Object>();
                        map.put("query", keyword);
                        map.put("operator", "and");
                        map.put("type", "phrase");
                        Map<String, Object> all = new HashMap<String, Object>();
                        all.put("_all", map);
                        Map<String, Object> match = new HashMap<String, Object>();
                        match.put("match", all);
                        Map<String, Object> query = new HashMap<String, Object>();
                        query.put("query", match);
                        filters.add(query);
                    }
                }
            }

            this.setTop(queryInfo.top);
            this.setSkip(queryInfo.skip);
            this.setOrderBy(queryInfo.orderBy);
            this.setSelect(queryInfo.select);
        }
        Map<String, Object> filter = new HashMap<String, Object>();
        if (!filters.isEmpty()) {
            Map<String, Object> and = new HashMap<String, Object>();
            and.put("filters", filters);
            filter.put("and", and);
        }
        this.source.put("filter", filter);

        // 暗黙条件の設定があるとき
        if (implicitConds != null && implicitConds.size() != 0) {
            Map<String, Object> query = QueryMapFactory.filteredQuery(null, QueryMapFactory.mustQuery(implicitConds));
            this.source.put("query", query);
        }

        // _versionを返却する
        this.source.put("version", true);
    }

    /**
     * @param top $topの値
     */
    public void setTop(Integer top) {
        if (top != null) {
            this.source.put("size", top);
        } else {
            this.source.put("size", DEFAULT_TOP_VALUE);
        }
    }

    /**
     * @param skip $skipの値
     */
    public void setSkip(Integer skip) {
        if (skip != null) {
            this.source.put("from", skip);
        }
    }

    /**
     * @param orderBy $orderByの値
     */
    public void setOrderBy(List<OrderByExpression> orderBy) {
        if (orderBy != null) {
            List<Map<String, Object>> sort = new ArrayList<Map<String, Object>>();

            for (OrderByExpression order : orderBy) {
                this.orderBy = new HashMap<String, Object>();
                order.visit(this);
                if (!this.orderBy.isEmpty()) {
                    sort.add(this.orderBy);
                }
            }
            this.source.put("sort", sort);
        }
    }

    /**
     * @param selects $selectの値
     */
    public void setSelect(List<EntitySimpleProperty> selects) {
        getSelectQuery(this.source, selects);
    }

    /**
     * $selectの値からES検索用のクエリを組立てる.
     * @param baseSource 入力値を格納したMap
     * @param selects $select
     */
    public void getSelectQuery(Map<String, Object> baseSource,
            List<EntitySimpleProperty> selects) {
        if (selects != null && selects.size() > 0) {
            // fieldsクエリの組立
            List<String> fields = new ArrayList<String>();
            fields.add(OEntityDocHandler.KEY_STATIC_FIELDS + "."
                    + Common.P_ID.getName());
            fields.add(OEntityDocHandler.KEY_PUBLISHED);
            fields.add(OEntityDocHandler.KEY_UPDATED);

            fields.add(OEntityDocHandler.KEY_CELL_ID);
            fields.add(OEntityDocHandler.KEY_BOX_ID);
            fields.add(OEntityDocHandler.KEY_NODE_ID);
            fields.add(OEntityDocHandler.KEY_ENTITY_ID);

            for (EntitySimpleProperty select : selects) {
                if (select == null) {
                    // $selectで指定された値がプロパティ名でなかった場合
                    throw DcCoreException.OData.SELECT_PARSE_ERROR;
                }
                String prop = select.getPropertyName();
                if (!Common.P_ID.getName().equals(prop)
                        && !Common.P_PUBLISHED.getName().equals(prop)
                        && !Common.P_UPDATED.getName().equals(prop)
                        && !"__metadata".equals(prop)) {
                    String fieldName = getFieldName(prop);
                    fields.add(fieldName);
                }
            }

            // selectのfield指定方法がEs0.19とEs1.X系とで異なる
            // この部分の差異をHelperが対応する
            EsQueryHandlerHelper.composeSourceFilter(baseSource, fields);
        }
    }

    /**
     * フィールド名を取得する.
     * @param prop プロパティ名
     * @return フィールド名
     */
    protected String getFieldName(String prop) {
        String fieldName = OEntityDocHandler.KEY_STATIC_FIELDS + "." + prop;
        return fieldName;
    }

    /**
     * 検索クエリを取得する.
     * @return 検索クエリ.
     */
    public Map<String, Object> getSource() {
        log.debug(this.source.toString());
        return this.source;
    }

    /**
     * 左辺処理前の共通処理.
     */
    @Override
    public void beforeDescend() {
    }

    /**
     * 左辺、右辺の処理後の共通処理.
     */
    @Override
    public void afterDescend() {
    }

    /**
     * 左辺処理後、右辺処理前の共通処理.
     */
    @Override
    public void betweenDescend() {
    }

    @Override
    public void visit(String type) {
        log.debug("visit(String type)");
    }

    @Override
    public void visit(OrderByExpression expr) {
        log.debug("visit(OrderByExpression expr)");
        if (!(expr.getExpression() instanceof EntitySimpleProperty)) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // ソートクエリを設定する
        String key = getSearchKey(expr.getExpression(), true);

        Map<String, Object> sortOption = new HashMap<String, Object>();
        sortOption.put("order", getOrderOption(expr.getDirection()));
        sortOption.put("ignore_unmapped", true);
        this.orderBy.put(key, sortOption);
    }

    /**
     * $orderbyのオプションを取得する.
     * @param option odata4jのオプション
     * @return optionValue 取得したオプション
     */
    public String getOrderOption(Direction option) {
        String optionValue;
        // デフォルト値は昇順(ASCENDING)
        if (option == null || option.equals(Direction.ASCENDING)) {
            optionValue = SORT_ASC;
        } else {
            optionValue = SORT_DESC;
        }
        return optionValue;
    }

    @Override
    public void visit(Direction direction) {
        log.debug("visit(Direction direction)");
    }

    @Override
    public void visit(AddExpression expr) {
        log.debug("visit(AddExpression expr)");
    }

    @Override
    public void visit(AndExpression expr) {
        log.debug("visit(AndExpression expr)");
        List<Object> andList = new ArrayList<Object>();
        Map<String, Object> lhs = new HashMap<String, Object>();
        Map<String, Object> rhs = new HashMap<String, Object>();
        andList.add(lhs);
        andList.add(rhs);
        this.current.put("and", andList);
        this.current = lhs;
        stack.push(rhs);
    }

    @Override
    public void visit(OrExpression expr) {
        log.debug("visit(OrExpression expr)");
        List<Object> orList = new ArrayList<Object>();
        Map<String, Object> lhs = new HashMap<String, Object>();
        Map<String, Object> rhs = new HashMap<String, Object>();
        orList.add(lhs);
        orList.add(rhs);
        this.current.put("or", orList);
        this.current = lhs;
        stack.push(rhs);
    }

    /**
     * 完全一致検索時のvisit.
     * @param expr EqExpression
     */
    @Override
    public void visit(EqExpression expr) {
        log.debug("visit(EqExpression expr)");

        // 左辺がプロパティ、右辺が文字列 int double boolean nullでない場合はパースエラーとする
        if (!(expr.getLHS() instanceof EntitySimpleProperty)
                || (!(expr.getRHS() instanceof StringLiteral)
                        && !(expr.getRHS() instanceof IntegralLiteral)
                        && !(expr.getRHS() instanceof Int64Literal)
                        && !(expr.getRHS() instanceof DoubleLiteral)
                        && !(expr.getRHS() instanceof BooleanLiteral)
                        && !(expr.getRHS() instanceof NullLiteral))) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // プロパティの型がBoolean型の場合はBooleanまたはnullの検索のみ許可する
        EntitySimpleProperty searchKey = (EntitySimpleProperty) expr.getLHS();
        CommonExpression searchValue = expr.getRHS();
        String propertyName = searchKey.getPropertyName();
        EdmProperty edmProperty = this.entityType.findProperty(propertyName);

        if ((edmProperty != null)
                && (EdmSimpleType.BOOLEAN.equals(edmProperty.getType()))
                && (!(searchValue instanceof BooleanLiteral || searchValue instanceof NullLiteral))) {
            throw DcCoreException.OData.UNKNOWN_PROPERTY_APPOINTED;
        }

        // 検索クエリを設定する
        // 検索対象がnullの場合、{"missing":{"field":"xxx"}}を作成する
        if (expr.getRHS() instanceof NullLiteral) {
            Map<String, Object> missing = new HashMap<String, Object>();
            missing.put("field", getSearchKey(expr.getLHS(), true));
            this.current.put("missing", missing);
            this.current = stack.pop();
        } else {
            // 検索対象がnull以外の場合、termクエリを作成する
            Map<String, Object> term = new HashMap<String, Object>();
            term.put(getSearchKey(expr.getLHS(), true), getSearchValue(expr.getRHS()));
            this.current.put("term", term);
            this.current = stack.pop();
        }
    }

    /**
     * elasticsearchの検索文字列を返却する.
     * @param expr CommonExpression
     * @return elasticsearchの検索文字列
     */
    private Object getSearchValue(CommonExpression expr) {
        if (expr instanceof IntegralLiteral) {
            return ((IntegralLiteral) expr).getValue();
        } else if (expr instanceof Int64Literal) {
            return ((Int64Literal) expr).getValue();
        } else if (expr instanceof DoubleLiteral) {
            return ((DoubleLiteral) expr).getValue();
        } else if (expr instanceof BooleanLiteral) {
            return ((BooleanLiteral) expr).getValue();
        } else {
            return ((StringLiteral) expr).getValue();
        }
    }

    /**
     * elasticsearchの検索キーを返却する.
     * @param expr CommonExpression
     * @return elasticsearchの検索キー
     */
    private String getSearchKey(CommonExpression expr) {
        return getSearchKey(expr, false);
    }

    /**
     * elasticsearchの検索キーを返却する.
     * @param expr CommonExpression
     * @param isUntouched isUntouched
     * @return elasticsearchの検索キー
     */
    protected String getSearchKey(CommonExpression expr, Boolean isUntouched) {
        // 検索キーとして設定を行う
        String keyName = ((EntitySimpleProperty) expr).getPropertyName();

        // published, updated
        if (Common.P_PUBLISHED.getName().equals(keyName)) {
            return OEntityDocHandler.KEY_PUBLISHED;
        } else if (Common.P_UPDATED.getName().equals(keyName)) {
            return OEntityDocHandler.KEY_UPDATED;
        }

        // スキーマ定義項目であればs.フィールド、定義外項目であればd.フィールドを検索する
        String fieldPrefix = OEntityDocHandler.KEY_STATIC_FIELDS + ".";

        // untouchedフィールドの指定であれば、untouchedを返却する
        if (isUntouched) {
            return fieldPrefix + keyName + ".untouched";
        } else {
            return fieldPrefix + keyName;
        }
    }

    @Override
    public void visit(BooleanLiteral expr) {
    }

    @Override
    public void visit(CastExpression expr) {
    }

    @Override
    public void visit(ConcatMethodCallExpression expr) {
    }

    @Override
    public void visit(DateTimeLiteral expr) {
    }

    @Override
    public void visit(DateTimeOffsetLiteral expr) {
    }

    @Override
    public void visit(DecimalLiteral expr) {
    }

    @Override
    public void visit(DivExpression expr) {
    }

    @Override
    public void visit(EndsWithMethodCallExpression expr) {
    }

    /**
     * EntitySimplePropertyのvisit.
     * @param expr EntitySimpleProperty
     */
    @Override
    public void visit(EntitySimpleProperty expr) {
    }

    @Override
    public void visit(GeExpression expr) {
        log.debug("visit(GeExpression expr)");

        // 左辺がプロパティ、右辺が文字列 int doubleでない場合はパースエラーとする
        if (!(expr.getLHS() instanceof EntitySimpleProperty)
                || (!(expr.getRHS() instanceof StringLiteral)
                        && !(expr.getRHS() instanceof IntegralLiteral)
                        && !(expr.getRHS() instanceof Int64Literal)
                        && !(expr.getRHS() instanceof DoubleLiteral))) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // ESの Range filterを設定する
        Map<String, Object> ge = new HashMap<String, Object>();
        Map<String, Object> property = new HashMap<String, Object>();
        ge.put("gte", getSearchValue(expr.getRHS()));
        property.put(getSearchKey(expr.getLHS(), true), ge);
        this.current.put("range", property);
        this.current = stack.pop();
    }

    @Override
    public void visit(GtExpression expr) {
        log.debug("visit(GtExpression expr)");

        // 左辺がプロパティ、右辺が文字列 int doubleでない場合はパースエラーとする
        if (!(expr.getLHS() instanceof EntitySimpleProperty)
                || (!(expr.getRHS() instanceof StringLiteral)
                        && !(expr.getRHS() instanceof IntegralLiteral)
                        && !(expr.getRHS() instanceof Int64Literal)
                        && !(expr.getRHS() instanceof DoubleLiteral))) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // ESの Range filterを設定する
        Map<String, Object> gt = new HashMap<String, Object>();
        Map<String, Object> property = new HashMap<String, Object>();
        gt.put("gt", getSearchValue(expr.getRHS()));
        property.put(getSearchKey(expr.getLHS(), true), gt);
        this.current.put("range", property);
        this.current = stack.pop();
    }

    @Override
    public void visit(GuidLiteral expr) {
    }

    @Override
    public void visit(BinaryLiteral expr) {
    }

    @Override
    public void visit(ByteLiteral expr) {
    }

    @Override
    public void visit(SByteLiteral expr) {
    }

    @Override
    public void visit(IndexOfMethodCallExpression expr) {
    }

    @Override
    public void visit(SingleLiteral expr) {
        log.debug("visit(SingleLiteral expr)");
    }

    @Override
    public void visit(DoubleLiteral expr) {
    }

    @Override
    public void visit(IntegralLiteral expr) {
    }

    @Override
    public void visit(Int64Literal expr) {
    }

    @Override
    public void visit(IsofExpression expr) {
    }

    @Override
    public void visit(LeExpression expr) {
        log.debug("visit(LeExpression expr)");

        // 左辺がプロパティ、右辺が文字列 int doubleでない場合はパースエラーとする
        if (!(expr.getLHS() instanceof EntitySimpleProperty)
                || (!(expr.getRHS() instanceof StringLiteral)
                        && !(expr.getRHS() instanceof IntegralLiteral)
                        && !(expr.getRHS() instanceof Int64Literal)
                        && !(expr.getRHS() instanceof DoubleLiteral))) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // ESの Range filterを設定する
        Map<String, Object> le = new HashMap<String, Object>();
        Map<String, Object> property = new HashMap<String, Object>();
        le.put("lte", getSearchValue(expr.getRHS()));
        property.put(getSearchKey(expr.getLHS(), true), le);
        this.current.put("range", property);
        this.current = stack.pop();
    }

    @Override
    public void visit(LengthMethodCallExpression expr) {
        log.debug("visit(LengthMethodCallExpression expr)");
    }

    @Override
    public void visit(LtExpression expr) {
        log.debug("visit(LtExpression expr)");

        // 左辺がプロパティ、右辺が文字列 int doubleでない場合はパースエラーとする
        if (!(expr.getLHS() instanceof EntitySimpleProperty)
                || (!(expr.getRHS() instanceof StringLiteral)
                        && !(expr.getRHS() instanceof IntegralLiteral)
                        && !(expr.getRHS() instanceof Int64Literal)
                        && !(expr.getRHS() instanceof DoubleLiteral))) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // ESの Range filterを設定する
        Map<String, Object> lt = new HashMap<String, Object>();
        Map<String, Object> property = new HashMap<String, Object>();
        lt.put("lt", getSearchValue(expr.getRHS()));
        property.put(getSearchKey(expr.getLHS(), true), lt);
        this.current.put("range", property);
        this.current = stack.pop();
    }

    @Override
    public void visit(ModExpression expr) {
    }

    @Override
    public void visit(MulExpression expr) {
    }

    @Override
    public void visit(NeExpression expr) {
    }

    @Override
    public void visit(NegateExpression expr) {
    }

    @Override
    public void visit(NotExpression expr) {
    }

    @Override
    public void visit(NullLiteral expr) {
    }

    @Override
    public void visit(ParenExpression expr) {
        log.debug("visit(ParenExpression expr)");
    }

    @Override
    public void visit(BoolParenExpression expr) {
    }

    @Override
    public void visit(ReplaceMethodCallExpression expr) {
    }

    @Override
    public void visit(StartsWithMethodCallExpression expr) {
        log.debug("visit(StartsWithMethodCallExpression expr)");

        // 左辺辺がプロパティ、右辺が文字列でない場合はパースエラーとする
        if (!(expr.getTarget() instanceof EntitySimpleProperty)
                || !(expr.getValue() instanceof StringLiteral)) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // 検索クエリを設定する
        Map<String, Object> prefix = new HashMap<String, Object>();

        prefix.put(getSearchKey(expr.getTarget(), true), getSearchValue(expr.getValue()));

        this.current.put("prefix", prefix);
        this.current = stack.pop();
    }

    /**
     * 文字列のvisit.
     * @param expr StringLiteral
     */
    @Override
    public void visit(StringLiteral expr) {
    }

    @Override
    public void visit(SubExpression expr) {
    }

    @Override
    public void visit(SubstringMethodCallExpression expr) {
    }

    @Override
    public void visit(SubstringOfMethodCallExpression expr) {
        log.debug("visit(SubstringOfMethodCallExpression expr)");

        // 左辺が文字列、右辺がプロパティでない場合はパースエラーとする
        if (!(expr.getTarget() instanceof EntitySimpleProperty)
                || !(expr.getValue() instanceof StringLiteral)) {
            throw DcCoreException.OData.FILTER_PARSE_ERROR;
        }

        // 検索クエリを設定する
        Map<String, Object> searchKey = new HashMap<String, Object>();
        Map<String, Object> query = new HashMap<String, Object>();
        Map<String, Object> text = new HashMap<String, Object>();

        searchKey.put("query", getSearchValue(expr.getValue()));
        searchKey.put("type", "phrase");
        text.put(getSearchKey(expr.getTarget()), searchKey);
        query.put("match", text);
        this.current.put("query", query);
        this.current = stack.pop();

    }

    @Override
    public void visit(TimeLiteral expr) {
    }

    @Override
    public void visit(ToLowerMethodCallExpression expr) {
    }

    @Override
    public void visit(ToUpperMethodCallExpression expr) {
    }

    @Override
    public void visit(TrimMethodCallExpression expr) {
    }

    @Override
    public void visit(YearMethodCallExpression expr) {
    }

    @Override
    public void visit(MonthMethodCallExpression expr) {
    }

    @Override
    public void visit(DayMethodCallExpression expr) {
    }

    @Override
    public void visit(HourMethodCallExpression expr) {
    }

    @Override
    public void visit(MinuteMethodCallExpression expr) {
    }

    @Override
    public void visit(SecondMethodCallExpression expr) {
    }

    @Override
    public void visit(RoundMethodCallExpression expr) {
    }

    @Override
    public void visit(FloorMethodCallExpression expr) {
    }

    @Override
    public void visit(CeilingMethodCallExpression expr) {
    }

    @Override
    public void visit(AggregateAnyFunction expr) {
    }

    @Override
    public void visit(AggregateAllFunction expr) {
    }
}
