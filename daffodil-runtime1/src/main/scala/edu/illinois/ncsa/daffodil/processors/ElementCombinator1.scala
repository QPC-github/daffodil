/* Copyright (c) 2012-2015 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 *
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 *
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */

package edu.illinois.ncsa.daffodil.processors

import edu.illinois.ncsa.daffodil.schema.annotation.props.gen.TestKind
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.dsom.DiagnosticUtils._
import edu.illinois.ncsa.daffodil.util.LogLevel
import edu.illinois.ncsa.daffodil.dpath.DFDLCheckConstraintsFunction
import edu.illinois.ncsa.daffodil.api.ValidationMode
import edu.illinois.ncsa.daffodil.util.Maybe
import edu.illinois.ncsa.daffodil.util.Maybe._
import edu.illinois.ncsa.daffodil.dpath.DFDLCheckConstraintsFunction
import edu.illinois.ncsa.daffodil.debugger._
import edu.illinois.ncsa.daffodil.dsom.CompiledExpression
import edu.illinois.ncsa.daffodil.processors.dfa.DFADelimiter

abstract class StatementElementParserBase(
  rd: RuntimeData,
  name: String,
  patDiscrimParser: Seq[Parser],
  patAssertParser: Seq[Parser],
  setVarParser: Seq[Parser],
  testDiscrimParser: Seq[Parser],
  testAssertParser: Seq[Parser],
  eParser: Option[Parser],
  eAfterParser: Option[Parser])
  extends Parser(rd) {

  Assert.invariant(testDiscrimParser.size <= 1)
  Assert.invariant(patDiscrimParser.size <= 1)

  def move(pstate: PState): Unit // implement for different kinds of "moving over to next thing"
  def parseBegin(pstate: PState): PState
  def parseEnd(pstate: PState): PState

  override lazy val childProcessors = patDiscrimParser ++ patAssertParser ++ eParser.toSeq ++ setVarParser ++ testDiscrimParser ++ testAssertParser ++ eAfterParser

  override def toBriefXML(depthLimit: Int = -1): String = {
    if (depthLimit == 0) "..." else
      "<Element name='" + name + "'>" +
        childProcessors.map { _.toBriefXML(depthLimit - 1) }.mkString +
        "</Element>"
  }

  def validate(pstate: PState): PState = {
    val currentElement = pstate.thisElement

    if (currentElement.valid.isDefined) { return pstate }

    val resultState = DFDLCheckConstraintsFunction.validate(pstate) match {
      case Right(boolVal) => {
        log(LogLevel.Debug, "Validation succeeded for %s", currentElement.toXML())
        currentElement.setValid(true)
        pstate // Success, do not mutate state.
      }
      case Left(failureMessage) => {
        log(LogLevel.Debug,
          "Validation failed for %s due to %s. The element value was %s.",
          context.toString, failureMessage, currentElement.toXML())
        pstate.withValidationError("%s failed dfdl:checkConstraints due to %s",
          context.toString, failureMessage)
        currentElement.setValid(false)
        pstate
      }
    }
    resultState
  }

  def parse(pstate: PState): PState = {
    //Removed checks now done at compilation

    val startingBitPos = pstate.bitPos
    val startingCharPos = pstate.charPos
    val startingRdr = pstate.inStream.reader
    var afterPatDisc = pstate //we're first so don't do .withPos(pstate.bitPos, pstate.charPos)
    patDiscrimParser.foreach(d => {
      afterPatDisc = d.parse1(afterPatDisc, rd)
      // Pattern fails at the start of the Element
      if (afterPatDisc.status != Success) { return afterPatDisc }
    })

    // now here we backup and run the pattern Asserts 
    // against the data at the start of the element's representation again.
    var afterPatAssrt = afterPatDisc.withPos(startingBitPos, startingCharPos, startingRdr)
    patAssertParser.foreach(d => {
      afterPatAssrt = d.parse1(afterPatAssrt, rd)
      // Pattern fails at the start of the Element
      if (afterPatAssrt.status != Success) { return afterPatAssrt }
    })

    // backup again. If all pattern discriminators and/or asserts
    // have passed, now we parse the element. But we backup
    // as if the pattern matching had not advanced the state.
    val beforeEState = afterPatAssrt.withPos(startingBitPos, startingCharPos, startingRdr)

    val postElementStartState = parseBegin(beforeEState)

    if (postElementStartState.status != Success) return postElementStartState

    // We just successfully created the element in the infoset. Notify the
    // debugger of this so it can do things like check for break points
    Debugger.startElement(postElementStartState, this)

    val postEState = eParser.map { eParser =>
      eParser.parse1(postElementStartState, rd)
    }.getOrElse(postElementStartState)

    var someSetVarFailed: Maybe[PState] = Nope

    var afterSetVar = postEState
    if (postEState.status == Success) {
      setVarParser.foreach(d => {
        val afterOneSetVar = d.parse1(afterSetVar, rd)
        if (afterOneSetVar.status == Success) {
          afterSetVar = afterOneSetVar
        } else {
          // a setVariable statement failed. But we want to continue to try 
          // more of the setVariable statements, as they may be necessary
          // to evaluate the test discriminator below, and some might 
          // be successful even if one fails, allowing the discriminator to be true.
          //
          // So it's a bit odd, but we're going to just keep parsing using this
          // failed state as the input to the next setVariable parse step.
          someSetVarFailed = One(afterOneSetVar)
          afterSetVar = afterOneSetVar
        }
      })
    }

    var afterTestDisc = afterSetVar
    testDiscrimParser.foreach(d => {
      afterTestDisc = d.parse1(afterTestDisc, rd)
      // Tests fail at the end of the Element
      if (afterTestDisc.status != Success) { return afterTestDisc }
    })

    // 
    // We're done with the discriminator, so now we revisit the set variable statements.
    // If a failure occurred there, then now we can fail out right here.
    // 
    someSetVarFailed.exists { return _ }

    // Element evaluation failed, return
    if (postEState.status != Success) { return postEState }

    var afterTestAssrt = afterTestDisc
    testAssertParser.foreach(d => {
      afterTestAssrt = d.parse1(afterTestAssrt, rd)
      // Tests fail at the end of the Element
      if (afterTestAssrt.status != Success) { return afterTestAssrt }
    })

    val postAfterState = eAfterParser.map { eAfterParser =>
      eAfterParser.parse1(afterTestAssrt, rd)
    }.getOrElse(afterTestAssrt)

    if (postAfterState.status != Success) return postAfterState

    val postElementEndState = parseEnd(postAfterState)

    postElementEndState //afterTestAssrt
  }
}

class StatementElementParser(
  erd: ElementRuntimeData,
  name: String,
  patDiscrim: Seq[Parser],
  patAssert: Seq[Parser],
  setVar: Seq[Parser],
  testDiscrim: Seq[Parser],
  testAssert: Seq[Parser],
  eParser: Option[Parser],
  eAfterParser: Option[Parser])
  extends StatementElementParserBase(
    erd,
    name,
    patDiscrim,
    patAssert,
    setVar,
    testDiscrim,
    testAssert,
    eParser,
    eAfterParser) {

  def move(start: PState) {
    start.mpstate.moveOverOneGroupIndexOnly
    start.mpstate.moveOverOneElementChildOnly
  }

  def parseBegin(pstate: PState): PState = {
    val currentElement = Infoset.newElement(erd)

    log(LogLevel.Debug, "currentElement = %s", currentElement)
    val priorElement = pstate.infoset
    priorElement match {
      case ct: DIComplex => ct.addChild(currentElement)
      case st: DISimple => {
        // don't add as a child. This corner case
        // is just about tests where the root node is 
        // a simple element. 
      }
    }
    log(LogLevel.Debug, "priorElement = %s", priorElement)
    val postState = pstate.withParent(currentElement)
    postState
  }

  def parseEnd(pstate: PState): PState = {
    val currentElement = pstate.thisElement

    val shouldValidate = pstate.dataProc.getValidationMode != ValidationMode.Off
    val postValidate =
      if (shouldValidate && erd.isSimpleType) {
        // Execute checkConstraints
        val resultState = validate(pstate)
        resultState
      } else pstate

    // Assert.invariant(currentElement.getName() != "_document_" )
    val priorElement = currentElement.parent
    // Note: interaction of unboxed Maybe[T] with pass by name args of log method
    // require us to call toScalaOption here.
    log(LogLevel.Debug, "priorElement = %s", priorElement.toScalaOption)
    val postState =
      if (priorElement.isDefined) postValidate.withParent(priorElement.get)
      else postValidate
    move(pstate)
    postState
  }
}

class StatementElementParserNoRep(
  erd: ElementRuntimeData,
  name: String,
  patDiscrim: Seq[Parser],
  patAssert: Seq[Parser],
  setVar: Seq[Parser],
  testDiscrim: Seq[Parser],
  testAssert: Seq[Parser],
  eParser: Option[Parser],
  eAfterParser: Option[Parser])
  extends StatementElementParser(
    erd,
    name,
    patDiscrim,
    patAssert,
    setVar,
    testDiscrim,
    testAssert,
    eParser,
    eAfterParser) {

  // if there is no rep (inputValueCalc), then we do create a new child so that index must advance,
  // but we don't create anything new as far as the group is concerned, and we don't want 
  // the group 'thinking' that there's a prior sibling inside the group and placing a 
  // separator after it. So in the case of NoRep, we don't advance group child, just element child.
  override def move(state: PState) {
    state.mpstate.moveOverOneElementChildOnly
  }
}

class ChoiceStatementElementParser(
  erd: ElementRuntimeData,
  name: String,
  patDiscrim: Seq[Parser],
  patAssert: Seq[Parser],
  setVar: Seq[Parser],
  testDiscrim: Seq[Parser],
  testAssert: Seq[Parser],
  eParser: Option[Parser],
  eAfterParser: Option[Parser])
  extends StatementElementParserBase(
    erd,
    name,
    patDiscrim,
    patAssert,
    setVar,
    testDiscrim,
    testAssert,
    eParser,
    eAfterParser) {

  def move(state: PState) = {}

  /**
   * ElementBegin just adds the element we are constructing to the infoset and changes
   * the state to be referring to this new element as what we're parsing data into.
   */
  def parseBegin(pstate: PState): PState = {
    val currentElement = Infoset.newElement(erd)

    log(LogLevel.Debug, "currentElement = %s", currentElement)
    pstate
  }

  // We don't want to modify the state here except
  // for validation.
  def parseEnd(pstate: PState): PState = {
    val currentElement = pstate.thisElement

    val shouldValidate = pstate.dataProc.getValidationMode != ValidationMode.Off
    val postValidate =
      if (shouldValidate && erd.isSimpleType) {
        // Execute checkConstraints
        val resultState = validate(pstate)
        resultState
      } else pstate

    val priorElement = currentElement.parent
    // Note: interaction of unboxed Maybe[T] with pass by name args of log method
    // require us to call toScalaOption here.
    log(LogLevel.Debug, "priorElement = %s", priorElement.toScalaOption)
    val postState = postValidate
    move(pstate)
    postState
  }
}
