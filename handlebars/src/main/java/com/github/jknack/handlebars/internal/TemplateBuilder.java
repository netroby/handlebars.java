/**
 * Copyright (c) 2012-2015 Edgar Espina
 *
 * This file is part of Handlebars.java.
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
package com.github.jknack.handlebars.internal;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.Validate.notNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.lang3.math.NumberUtils;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Decorator;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.HandlebarsError;
import com.github.jknack.handlebars.HandlebarsException;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.HelperRegistry;
import com.github.jknack.handlebars.TagType;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.EachHelper;
import com.github.jknack.handlebars.internal.HbsParser.AmpvarContext;
import com.github.jknack.handlebars.internal.HbsParser.BlockContext;
import com.github.jknack.handlebars.internal.HbsParser.BlockParamsContext;
import com.github.jknack.handlebars.internal.HbsParser.BodyContext;
import com.github.jknack.handlebars.internal.HbsParser.BoolParamContext;
import com.github.jknack.handlebars.internal.HbsParser.CharParamContext;
import com.github.jknack.handlebars.internal.HbsParser.CommentContext;
import com.github.jknack.handlebars.internal.HbsParser.DynamicPathContext;
import com.github.jknack.handlebars.internal.HbsParser.ElseBlockContext;
import com.github.jknack.handlebars.internal.HbsParser.ElseStmtChainContext;
import com.github.jknack.handlebars.internal.HbsParser.ElseStmtContext;
import com.github.jknack.handlebars.internal.HbsParser.EscapeContext;
import com.github.jknack.handlebars.internal.HbsParser.HashContext;
import com.github.jknack.handlebars.internal.HbsParser.IntParamContext;
import com.github.jknack.handlebars.internal.HbsParser.NewlineContext;
import com.github.jknack.handlebars.internal.HbsParser.ParamContext;
import com.github.jknack.handlebars.internal.HbsParser.PartialBlockContext;
import com.github.jknack.handlebars.internal.HbsParser.PartialContext;
import com.github.jknack.handlebars.internal.HbsParser.RawBlockContext;
import com.github.jknack.handlebars.internal.HbsParser.RefParamContext;
import com.github.jknack.handlebars.internal.HbsParser.SexprContext;
import com.github.jknack.handlebars.internal.HbsParser.SpacesContext;
import com.github.jknack.handlebars.internal.HbsParser.StatementContext;
import com.github.jknack.handlebars.internal.HbsParser.StaticPathContext;
import com.github.jknack.handlebars.internal.HbsParser.StringParamContext;
import com.github.jknack.handlebars.internal.HbsParser.SubParamExprContext;
import com.github.jknack.handlebars.internal.HbsParser.TemplateContext;
import com.github.jknack.handlebars.internal.HbsParser.TextContext;
import com.github.jknack.handlebars.internal.HbsParser.TvarContext;
import com.github.jknack.handlebars.internal.HbsParser.UnlessContext;
import com.github.jknack.handlebars.internal.HbsParser.VarContext;
import com.github.jknack.handlebars.io.TemplateSource;

/**
 * Traverse the parse tree and build templates.
 *
 * @author edgar.espina
 * @param <it>
 * @since 0.10.0
 */
abstract class TemplateBuilder<it> extends HbsParserBaseVisitor<Object> {

  /**
   * Get partial info: static vs dynamic.
   *
   * @author edgar
   * @since 2.2.0
   */
  private static class PartialInfo {

    /** Token to report errors. */
    private Token token;

    /** Partial params. */
    private Map<String, Object> hash;

    /** Partial path: static vs subexpression. */
    private Template path;

    /** Template context. */
    private String context;

  }

  /**
   * A handlebars object. required.
   */
  private Handlebars handlebars;

  /**
   * The template source. Required.
   */
  private TemplateSource source;

  /**
   * Flag to track dead spaces and lines.
   */
  private Boolean hasTag;

  /**
   * Keep track of the current line.
   */
  protected StringBuilder line = new StringBuilder();

  /**
   * Keep track of block helpers.
   */
  private LinkedList<String> qualifier = new LinkedList<String>();

  /** Keep track of block level, required for top level decorators. */
  private int level;

  /**
   * Creates a new {@link TemplateBuilder}.
   *
   * @param handlebars A handlbars object. required.
   * @param source The template source. required.
   */
  public TemplateBuilder(final Handlebars handlebars, final TemplateSource source) {
    this.handlebars = notNull(handlebars, "The handlebars can't be null.");
    this.source = notNull(source, "The template source is requied.");
  }

  @Override
  public Template visit(final ParseTree tree) {
    return (Template) super.visit(tree);
  }

  @Override
  public Template visitRawBlock(final RawBlockContext ctx) {
    level += 1;
    SexprContext sexpr = ctx.sexpr();
    Token nameStart = sexpr.QID().getSymbol();
    String name = nameStart.getText();
    qualifier.addLast(name);
    String nameEnd = ctx.nameEnd.getText();
    if (!name.equals(nameEnd)) {
      reportError(null, ctx.nameEnd.getLine(), ctx.nameEnd.getCharPositionInLine(),
          String.format("found: '%s', expected: '%s'", nameEnd, name));
    }

    hasTag(true);
    Block block = new Block(handlebars, name, false, "{{", params(sexpr.param()),
        hash(sexpr.hash()), Collections.<String> emptyList());
    block.filename(source.filename());
    block.position(nameStart.getLine(), nameStart.getCharPositionInLine());
    String startDelim = ctx.start.getText();
    startDelim = startDelim.substring(0, startDelim.length() - 2);
    block.startDelimiter(startDelim);
    block.endDelimiter(ctx.stop.getText());

    Template body = visitBody(ctx.thenBody);
    if (body != null) {
      block.body(body);
    }
    hasTag(true);
    qualifier.removeLast();
    level -= 1;
    return block;
  }

  @Override
  public Template visitBlock(final BlockContext ctx) {
    level += 1;
    SexprContext sexpr = ctx.sexpr();
    boolean decorator = ctx.DECORATOR() != null;
    Token nameStart = sexpr.QID().getSymbol();
    String name = nameStart.getText();
    qualifier.addLast(name);
    String nameEnd = ctx.nameEnd.getText();
    if (!name.equals(nameEnd)) {
      reportError(null, ctx.nameEnd.getLine(), ctx.nameEnd.getCharPositionInLine(),
          String.format("found: '%s', expected: '%s'", nameEnd, name));
    }

    hasTag(true);
    Block block = null;
    if (decorator) {
      block = new BlockDecorator(handlebars, name, false, params(sexpr.param()),
          hash(sexpr.hash()), blockParams(ctx.blockParams()), level == 1);
    } else {
      // favor well known helpers over default/mustache versions helper.
      if ("each".equals(name) && handlebars.helper(name) == EachHelper.INSTANCE) {
        // override default each helper
        block = new EachBlock(handlebars, name, false, "#", params(sexpr.param()),
            hash(sexpr.hash()), blockParams(ctx.blockParams()));
      }
      if (block == null) {
        block = new Block(handlebars, name, false, "#", params(sexpr.param()),
            hash(sexpr.hash()), blockParams(ctx.blockParams()));
      }
    }
    block.filename(source.filename());
    block.position(nameStart.getLine(), nameStart.getCharPositionInLine());
    String startDelim = ctx.start.getText();
    startDelim = startDelim.substring(0, startDelim.length() - 1);
    block.startDelimiter(startDelim);
    block.endDelimiter(ctx.stop.getText());

    Template body = visitBody(ctx.thenBody);
    if (body != null) {
      block.body(body);
    }
    // else
    Block elseroot = block;
    for (ElseBlockContext elseBlock : ctx.elseBlock()) {
      ElseStmtContext elseStmt = elseBlock.elseStmt();
      if (elseStmt != null) {
        // basic else
        Template unless = visitBody(elseStmt.unlessBody);
        if (unless != null) {
          String inverseLabel = elseStmt.inverseToken.getText();
          if (inverseLabel.startsWith(startDelim)) {
            inverseLabel = inverseLabel.substring(startDelim.length());
          }
          elseroot.inverse(inverseLabel, unless);
        }
      } else {
        // else chain
        ElseStmtChainContext elseStmtChain = elseBlock.elseStmtChain();
        SexprContext elseexpr = elseStmtChain.sexpr();
        Token elsenameStart = elseexpr.QID().getSymbol();
        String elsename = elsenameStart.getText();
        Block elseblock = new Block(handlebars, elsename, false, "#", params(elseexpr.param()),
            hash(elseexpr.hash()), blockParams(elseStmtChain.blockParams()));
        elseblock.filename(source.filename());
        elseblock.position(elsenameStart.getLine(), elsenameStart.getCharPositionInLine());
        elseblock.startDelimiter(startDelim);
        elseblock.endDelimiter(elseBlock.stop.getText());
        Template elsebody = visitBody(elseStmtChain.unlessBody);
        elseblock.body(elsebody);

        String inverseLabel = elseStmtChain.inverseToken.getText();
        if (inverseLabel.startsWith(startDelim)) {
          inverseLabel = inverseLabel.substring(startDelim.length());
        }
        elseroot.inverse(inverseLabel, elseblock);
        elseroot = elseblock;
      }
    }
    hasTag(true);
    qualifier.removeLast();
    level -= 1;
    return block;
  }

  @Override
  public Template visitUnless(final UnlessContext ctx) {
    level += 1;
    hasTag(true);
    SexprContext sexpr = ctx.sexpr();
    Token nameStart = sexpr.QID().getSymbol();
    String name = nameStart.getText();
    qualifier.addLast(name);
    String nameEnd = ctx.nameEnd.getText();
    if (!name.equals(nameEnd)) {
      reportError(null, ctx.nameEnd.getLine(), ctx.nameEnd.getCharPositionInLine(),
          String.format("found: '%s', expected: '%s'", nameEnd, name));
    }
    Block block = new Block(handlebars, name, true, "^", Collections.emptyList(),
        Collections.<String, Object> emptyMap(), blockParams(ctx.blockParams()));
    block.filename(source.filename());
    block.position(nameStart.getLine(), nameStart.getCharPositionInLine());
    String startDelim = ctx.start.getText();
    block.startDelimiter(startDelim.substring(0, startDelim.length() - 1));
    block.endDelimiter(ctx.stop.getText());

    Template body = visitBody(ctx.body());
    if (body != null) {
      block.body(body);
    }
    hasTag(true);
    level -= 1;
    return block;
  }

  @Override
  public Template visitVar(final VarContext ctx) {
    hasTag(false);
    SexprContext sexpr = ctx.sexpr();
    return newVar(sexpr.QID().getSymbol(), TagType.VAR, params(sexpr.param()), hash(sexpr.hash()),
        ctx.start.getText(), ctx.stop.getText(), ctx.DECORATOR() != null);
  }

  @Override
  public Object visitEscape(final EscapeContext ctx) {
    Token token = ctx.ESC_VAR().getSymbol();
    String text = token.getText().substring(1);
    line.append(text);
    return new Text(handlebars, text, "\\")
        .filename(source.filename())
        .position(token.getLine(), token.getCharPositionInLine());
  }

  @Override
  public Template visitTvar(final TvarContext ctx) {
    hasTag(false);
    SexprContext sexpr = ctx.sexpr();
    return newVar(sexpr.QID().getSymbol(), TagType.TRIPLE_VAR, params(sexpr.param()),
        hash(sexpr.hash()),
        ctx.start.getText(), ctx.stop.getText(), false);
  }

  @Override
  public Template visitAmpvar(final AmpvarContext ctx) {
    hasTag(false);
    SexprContext sexpr = ctx.sexpr();
    return newVar(sexpr.QID().getSymbol(), TagType.AMP_VAR, params(sexpr.param()),
        hash(sexpr.hash()),
        ctx.start.getText(), ctx.stop.getText(), false);
  }

  /**
   * Build a new {@link Variable}.
   *
   * @param name The var's name.
   * @param varType The var's type.
   * @param params The var params.
   * @param hash The var hash.
   * @param startDelimiter The current start delimiter.
   * @param endDelimiter The current end delimiter.
   * @param decorator True, for var decorators.
   * @return A new {@link Variable}.
   */
  private Template newVar(final Token name, final TagType varType, final List<Object> params,
      final Map<String, Object> hash, final String startDelimiter, final String endDelimiter,
      final boolean decorator) {
    String varName = name.getText();
    boolean isHelper = ((params.size() > 0 || hash.size() > 0)
        || varType == TagType.SUB_EXPRESSION);
    if (!isHelper && qualifier.size() > 0 && "with".equals(qualifier.getLast())
        && !varName.startsWith(".")) {
      // HACK to qualified 'with' in order to improve handlebars.js compatibility
      varName = "this." + varName;
    }
    String[] parts = varName.split("\\./");
    // TODO: try to catch this with ANTLR...
    // foo.0 isn't allowed, it must be foo.0.
    if (parts.length > 0 && NumberUtils.isNumber(parts[parts.length - 1])
        && !varName.endsWith(".")) {
      String evidence = varName;
      String reason = "found: " + varName + ", expecting: " + varName + ".";
      String message = source.filename() + ":" + name.getLine() + ":" + name.getChannel() + ": "
          + reason + "\n";
      throw new HandlebarsException(new HandlebarsError(source.filename(), name.getLine(),
          name.getCharPositionInLine(), reason, evidence, message));
    }
    if (decorator) {
      Decorator dec = handlebars.decorator(varName);
      if (dec == null) {
        reportError(null, name.getLine(), name.getCharPositionInLine(),
            "could not find decorator: '" + varName + "'");
      }
    } else {
      Helper<Object> helper = handlebars.helper(varName);
      if (helper == null && isHelper) {
        Helper<Object> helperMissing = handlebars.helper(HelperRegistry.HELPER_MISSING);
        if (helperMissing == null) {
          reportError(null, name.getLine(), name.getCharPositionInLine(), "could not find helper: '"
              + varName + "'");
        }
      }
    }
    Variable var = decorator
        ? new VarDecorator(handlebars, varName, TagType.STAR_VAR, params, hash, level == 0)
        : new Variable(handlebars, varName, varType, params, hash);
    return var
        .startDelimiter(startDelimiter)
        .endDelimiter(endDelimiter)
        .filename(source.filename())
        .position(name.getLine(), name.getCharPositionInLine());
  }

  /**
   * Build a hash.
   *
   * @param ctx The hash context.
   * @return A new hash.
   */
  private Map<String, Object> hash(final List<HashContext> ctx) {
    if (ctx == null || ctx.size() == 0) {
      return Collections.emptyMap();
    }
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    for (HashContext hc : ctx) {
      result.put(hc.QID().getText(), super.visit(hc.param()));
    }
    return result;
  }

  /**
   * Build a hash.
   *
   * @param ctx The hash context.
   * @return A new hash.
   */
  private List<String> blockParams(final BlockParamsContext ctx) {
    if (ctx == null) {
      return Collections.emptyList();
    }
    List<TerminalNode> ids = ctx.QID();
    if (ids == null || ids.size() == 0) {
      return Collections.emptyList();
    }
    List<String> result = new ArrayList<String>();
    for (TerminalNode id : ids) {
      result.add(id.getText());
    }
    return result;
  }

  /**
   * Build a param list.
   *
   * @param params The param context.
   * @return A new param list.
   */
  private List<Object> params(final List<ParamContext> params) {
    if (params == null || params.size() == 0) {
      return Collections.emptyList();
    }
    List<Object> result = new ArrayList<Object>();
    for (ParamContext param : params) {
      result.add(super.visit(param));
    }
    return result;
  }

  @Override
  public Object visitBoolParam(final BoolParamContext ctx) {
    return Boolean.valueOf(ctx.getText());
  }

  @Override
  public Object visitSubParamExpr(final SubParamExprContext ctx) {
    SexprContext sexpr = ctx.sexpr();
    return newVar(sexpr.QID().getSymbol(), TagType.SUB_EXPRESSION, params(sexpr.param()),
        hash(sexpr.hash()), ctx.start.getText(), ctx.stop.getText(), false);
  }

  @Override
  public Object visitStringParam(final StringParamContext ctx) {
    return stringLiteral(ctx);
  }

  @Override
  public Object visitCharParam(final CharParamContext ctx) {
    return charLiteral(ctx);
  }

  /**
   * @param ctx The char literal context.
   * @return A char literal.
   */
  private String charLiteral(final RuleContext ctx) {
    return ctx.getText().replace("\\\'", "\'");
  }

  /**
   * @param ctx The string literal context.
   * @return A string literal.
   */
  private String stringLiteral(final RuleContext ctx) {
    return ctx.getText().replace("\\\"", "\"");
  }

  @Override
  public Object visitRefParam(final RefParamContext ctx) {
    return ctx.getText();
  }

  @Override
  public Object visitIntParam(final IntParamContext ctx) {
    return Integer.parseInt(ctx.getText());
  }

  @Override
  public Template visitTemplate(final TemplateContext ctx) {
    Template template = visitBody(ctx.body());
    if (!handlebars.infiniteLoops() && template instanceof BaseTemplate) {
      template = infiniteLoop(source, (BaseTemplate) template);
    }
    destroy();
    return template;
  }

  /**
   * Creates a {@link Template} that detects recursively calls.
   *
   * @param source The template source.
   * @param template The original template.
   * @return A new {@link Template} that detects recursively calls.
   */
  private static Template infiniteLoop(final TemplateSource source, final BaseTemplate template) {
    return new ForwardingTemplate(template) {
      @Override
      protected void beforeApply(final Context context) {
        LinkedList<TemplateSource> invocationStack = context.data(Context.INVOCATION_STACK);
        invocationStack.addLast(source);
      }

      @Override
      protected void afterApply(final Context context) {
        LinkedList<TemplateSource> invocationStack = context.data(Context.INVOCATION_STACK);
        if (!invocationStack.isEmpty()) {
          invocationStack.removeLast();
        }
      }

    };
  }

  @Override
  public Template visitPartial(final PartialContext ctx) {
    hasTag(true);

    String indent = line.toString();
    if (hasTag()) {
      if (isEmpty(indent) || !isEmpty(indent.trim())) {
        indent = null;
      }
    } else {
      indent = null;
    }

    PartialInfo info = (PartialInfo) super.visit(ctx.pexpr());

    String startDelim = ctx.start.getText();
    Template partial = new Partial(handlebars, info.path, info.context, info.hash)
        .startDelimiter(startDelim.substring(0, startDelim.length() - 1))
        .endDelimiter(ctx.stop.getText())
        .indent(indent)
        .filename(source.filename())
        .position(info.token.getLine(), info.token.getCharPositionInLine());

    return partial;
  }

  @Override
  public Object visitPartialBlock(final PartialBlockContext ctx) {
    hasTag(true);

    String indent = line.toString();
    if (hasTag()) {
      if (isEmpty(indent) || !isEmpty(indent.trim())) {
        indent = null;
      }
    } else {
      indent = null;
    }

    PartialInfo info = (PartialInfo) super.visit(ctx.pexpr());
    Template fn = visitBody(ctx.thenBody);

    String startDelim = ctx.start.getText();
    Template partial = new Partial(handlebars, info.path, info.context, info.hash)
        .setPartial(fn)
        .startDelimiter(startDelim.substring(0, startDelim.length() - 1))
        .endDelimiter(ctx.stop.getText())
        .indent(indent)
        .filename(source.filename())
        .position(info.token.getLine(), info.token.getCharPositionInLine());

    return partial;
  }

  @Override
  public PartialInfo visitStaticPath(final StaticPathContext ctx) {
    Token pathToken = ctx.path;
    String uri = pathToken.getText();
    if (uri.startsWith("[") && uri.endsWith("]")) {
      uri = uri.substring(1, uri.length() - 1);
    }

    if (uri.startsWith("/")) {
      String message = "found: '/', partial shouldn't start with '/'";
      reportError(null, pathToken.getLine(), pathToken.getCharPositionInLine(), message);
    }

    TerminalNode partialContext = ctx.QID(1);

    PartialInfo partial = new PartialInfo();
    partial.token = pathToken;
    partial.path = new Text(handlebars, uri);
    partial.hash = hash(ctx.hash());
    partial.context = partialContext != null ? partialContext.getText() : null;
    return partial;
  }

  @Override
  public PartialInfo visitDynamicPath(final DynamicPathContext ctx) {
    SexprContext sexpr = ctx.sexpr();
    TerminalNode qid = sexpr.QID();
    Template expression = newVar(qid.getSymbol(), TagType.SUB_EXPRESSION, params(sexpr.param()),
        hash(sexpr.hash()), ctx.start.getText(), ctx.stop.getText(), false);

    PartialInfo partial = new PartialInfo();
    partial.path = expression;
    partial.hash = hash(ctx.hash());
    partial.context = null;
    partial.token = qid.getSymbol();
    return partial;
  }

  @Override
  public Template visitBody(final BodyContext ctx) {
    List<StatementContext> stats = ctx.statement();
    if (stats.size() == 0 || (stats.size() == 1 && stats.get(0) == Template.EMPTY)) {
      return Template.EMPTY;
    }

    TemplateList list = new TemplateList(handlebars);
    list.filename(source.filename());
    Template prev = null;
    boolean setMd = false;
    for (StatementContext statement : stats) {
      Template candidate = visit(statement);
      if (candidate != null) {
        if (!setMd) {
          list.filename(candidate.filename())
              .position(candidate.position()[0], candidate.position()[1]);
          setMd = true;
        }
        // join consecutive text
        if (candidate instanceof Text) {
          if (!(prev instanceof Text)) {
            list.add(candidate);
            prev = candidate;
          } else {
            ((Text) prev).append(((Text) candidate).textWithoutEscapeChar());
          }
        } else {
          list.add(candidate);
          prev = candidate;
        }
      }
    }

    return list;
  }

  @Override
  public Object visitComment(final CommentContext ctx) {
    return Template.EMPTY;
  }

  @Override
  public Template visitStatement(final StatementContext ctx) {
    return visit(ctx.getChild(0));
  }

  @Override
  public Template visitText(final TextContext ctx) {
    String text = ctx.getText();
    line.append(text);
    return new Text(handlebars, text)
        .filename(source.filename())
        .position(ctx.start.getLine(), ctx.start.getCharPositionInLine());
  }

  @Override
  public Template visitSpaces(final SpacesContext ctx) {
    Token space = ctx.SPACE().getSymbol();
    String text = space.getText();
    line.append(text);
    if (space.getChannel() == Token.HIDDEN_CHANNEL) {
      return null;
    }
    return new Text(handlebars, text)
        .filename(source.filename())
        .position(ctx.start.getLine(), ctx.start.getCharPositionInLine());
  }

  @Override
  public BaseTemplate visitNewline(final NewlineContext ctx) {
    Token newline = ctx.NL().getSymbol();
    line.setLength(0);
    hasTag = null;
    if (newline.getChannel() == Token.HIDDEN_CHANNEL) {
      return null;
    }
    return new Text(handlebars, newline.getText())
        .filename(source.filename())
        .position(newline.getLine(), newline.getCharPositionInLine());
  }

  /**
   * True, if tag instruction was processed.
   *
   * @return True, if tag instruction was processed.
   */
  private boolean hasTag() {
    if (handlebars.prettyPrint()) {
      return hasTag == null ? false : hasTag.booleanValue();
    }
    return false;
  }

  /**
   * Set if a new tag instruction was processed.
   *
   * @param hasTag True, if a new tag instruction was processed.
   */
  private void hasTag(final boolean hasTag) {
    if (this.hasTag != Boolean.FALSE) {
      this.hasTag = hasTag;
    }
  }

  /**
   * Cleanup resources.
   */
  private void destroy() {
    this.handlebars = null;
    this.source = null;
    this.hasTag = null;
    this.line.delete(0, line.length());
    this.line = null;
  }

  /**
   * Report a semantic error.
   *
   * @param offendingToken The offending token.
   * @param message An error message.
   */
  protected void reportError(final CommonToken offendingToken, final String message) {
    reportError(offendingToken, offendingToken.getLine(), offendingToken.getCharPositionInLine(),
        message);
  }

  /**
   * Report a semantic error.
   *
   * @param offendingToken The offending token.
   * @param line The offending line.
   * @param column The offending column.
   * @param message An error message.
   */
  protected abstract void reportError(final CommonToken offendingToken, final int line,
      final int column, final String message);
}
