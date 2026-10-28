package soot.jimple.toolkits.callgraph;

/*-
 * #%L
 * Soot - a J*va Optimization Framework
 * %%
 * Copyright (C) 2026 Soot contributors
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Test;

import soot.Body;
import soot.ArrayType;
import soot.G;
import soot.Kind;
import soot.Local;
import soot.MethodSubSignature;
import soot.Modifier;
import soot.PackManager;
import soot.PhaseOptions;
import soot.RefType;
import soot.Scene;
import soot.SootField;
import soot.PointsToSet;
import soot.SootClass;
import soot.SootMethod;
import soot.Transform;
import soot.VoidType;
import soot.jimple.Jimple;
import soot.jimple.IntConstant;
import soot.jimple.NullConstant;
import soot.jimple.Stmt;
import soot.jimple.spark.SparkTransformer;
import soot.jimple.spark.ondemand.DemandCSPointsTo;
import soot.jimple.spark.ondemand.pautil.ContextSensitiveInfo;
import soot.jimple.spark.builder.ContextInsensitiveBuilder;
import soot.jimple.spark.pag.PAG;
import soot.jimple.spark.solver.OnFlyCallGraph;
import soot.jimple.toolkits.callgraph.VirtualEdgesSummaries.DirectParameterMapping;
import soot.jimple.toolkits.callgraph.VirtualEdgesSummaries.DirectTarget;
import soot.jimple.toolkits.callgraph.VirtualEdgesSummaries.InstanceinvokeSource;
import soot.jimple.toolkits.callgraph.VirtualEdgesSummaries.StaticinvokeSource;
import soot.jimple.toolkits.callgraph.VirtualEdgesSummaries.VirtualEdge;
import soot.jimple.toolkits.callgraph.VirtualEdgesSummaries.VirtualEdgeSource;
import soot.options.Options;

/** Tests local summary selection and the resulting call graph/points-to fixed point. */
public class CallSiteVirtualEdgesTest {
  private static PAG capturedPAG;
  private static VirtualEdgesSummaries defaultSummaries;
  private SootClass dispatcher;
  private SootClass callback;
  private SootClass payload;
  private SootMethod schedule;
  private Stmt call;

  @Before
  public void setUp() {
    G.reset();
    defaultSummaries = null;
    Options.v().set_allow_phantom_refs(true);
    Options.v().set_prepend_classpath(true);
    Scene.v().loadBasicClasses();
    payload = newClass("example.Payload", true);
    callback = new SootClass("example.Callback", Modifier.PUBLIC | Modifier.INTERFACE | Modifier.ABSTRACT);
    Scene.v().addClass(callback);
    callback.setLibraryClass();
    callback.addMethod(new SootMethod("accept", Collections.singletonList(payload.getType()), VoidType.v(),
        Modifier.PUBLIC | Modifier.ABSTRACT));
    dispatcher = newClass("example.Dispatcher", false);
    schedule = new SootMethod("schedule", Arrays.asList(callback.getType(), payload.getType()), VoidType.v(),
        Modifier.PUBLIC | Modifier.NATIVE);
    dispatcher.addMethod(schedule);
    Local receiver = Jimple.v().newLocal("dispatcher", dispatcher.getType());
    Local task = Jimple.v().newLocal("task", callback.getType());
    Local value = Jimple.v().newLocal("value", payload.getType());
    call = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(receiver, schedule.makeRef(), task, value));
  }

  @Test
  public void untaggedCallKeepsTheExistingSummary() {
    VirtualEdge existing = summary(call);
    VirtualEdgesSummaries summaries = new VirtualEdgesSummaries(Collections.singletonList(existing));
    assertSame(existing, summaries.getVirtualEdgesMatchingCallSite(call));
  }

  @Test
  public void localOverrideDoesNotAffectAnotherCallToTheSameApi() {
    VirtualEdge existing = summary(call);
    VirtualEdgesSummaries summaries = new VirtualEdgesSummaries(Collections.singletonList(existing));
    VirtualEdge local = summary(call);
    call.addTag(new VirtualEdgeSummaryTag(local));
    assertSame(local, summaries.getVirtualEdgesMatchingCallSite(call));
    assertSame(existing, summaries.getVirtualEdgesMatchingCallSite(Jimple.v().newInvokeStmt(call.getInvokeExpr())));
  }

  @Test
  public void localSuppressionDoesNotRemoveTheGlobalSummary() {
    VirtualEdge existing = summary(call);
    VirtualEdgesSummaries summaries = new VirtualEdgesSummaries(Collections.singletonList(existing));
    call.addTag(new VirtualEdgeSummaryTag(null));
    assertNull(summaries.getVirtualEdgesMatchingCallSite(call));
    call.removeTag(VirtualEdgeSummaryTag.NAME);
    assertSame(existing, summaries.getVirtualEdgesMatchingCallSite(call));
  }

  @Test(expected = IllegalArgumentException.class)
  public void multipleTargetsAreRejectedUntilTheirMappingsCanBeDistinguished() {
    VirtualEdge local = summary(call);
    local.getTargets().add(new DirectTarget(callback.getType(),
        new MethodSubSignature(callback.getMethod("void accept(example.Payload)").makeRef()), 1));
    new VirtualEdgeSummaryTag(local);
  }

  @Test(expected = IllegalArgumentException.class)
  public void unsupportedEdgeKindsAreRejectedBeforeAnalysis() {
    VirtualEdge local = summary(call);
    new VirtualEdgeSummaryTag(new VirtualEdge(Kind.THREAD, local.getSource(), local.getTargets()));
  }

  @Test
  public void staticCallSummaryPreservesItsTargetAndMapsArguments() {
    assertLocalSummaryPropagation(InvocationKind.STATIC, false);
  }

  @Test
  public void specialCallSummaryPreservesItsTargetAndMapsArguments() {
    assertLocalSummaryPropagation(InvocationKind.SPECIAL, false);
  }

  @Test
  public void localSummaryDoesNotRequireGlobalSourceMetadata() {
    assertLocalSummaryPropagation(InvocationKind.VIRTUAL, true);
  }

  private enum InvocationKind {
    VIRTUAL, STATIC, INTERFACE, SPECIAL
  }

  private void assertLocalSummaryPropagation(InvocationKind kind, boolean omitSource) {
    SootMethod target = callbackMethod("example.SelectedTask");
    Body targetBody = instanceBody(target, true);
    targetBody.getUnits().add(Jimple.v().newReturnVoidStmt());
    SootMethod api = schedule;
    if (kind == InvocationKind.STATIC) {
      api = new SootMethod("scheduleStatic", schedule.getParameterTypes(), VoidType.v(),
          Modifier.PUBLIC | Modifier.STATIC | Modifier.NATIVE);
      dispatcher.addMethod(api);
    }
    SootClass app = newClass("example.CallSiteApplication", true);
    if (kind == InvocationKind.SPECIAL) {
      app.setSuperclass(dispatcher);
    }
    SootMethod entry = new SootMethod("entry", Collections.emptyList(), VoidType.v(),
        Modifier.PUBLIC | (kind == InvocationKind.SPECIAL ? 0 : Modifier.STATIC));
    app.addMethod(entry);
    Body body = kind == InvocationKind.SPECIAL ? instanceBody(entry, false) : Jimple.v().newBody(entry);
    entry.setActiveBody(body);
    Local task = allocate(body, "task", target.getDeclaringClass());
    Local value = allocate(body, "payload", payload);
    Stmt invocation;
    VirtualEdgeSource source;
    if (kind == InvocationKind.STATIC) {
      invocation = Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(api.makeRef(), task, value));
      source = new StaticinvokeSource(api.getSignature());
    } else if (kind == InvocationKind.SPECIAL) {
      invocation = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(body.getThisLocal(), api.makeRef(), task, value));
      source = new InstanceinvokeSource(invocation);
    } else {
      Local receiver = allocate(body, "dispatcher", dispatcher);
      invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(receiver, api.makeRef(), task, value));
      source = new InstanceinvokeSource(invocation);
    }
    body.getUnits().add(invocation);
    body.getUnits().add(Jimple.v().newReturnVoidStmt());
    DirectTarget direct = new DirectTarget(callback.getType(), new MethodSubSignature(target.makeRef()), 0);
    direct.getParameterMappings().add(new DirectParameterMapping(1, 0));
    VirtualEdge local = new VirtualEdge(Kind.GENERIC_FAKE, omitSource ? null : source, direct);
    runWithCustomBuilder(entry, method -> {
      if (method == entry) {
        invocation.addTag(new VirtualEdgeSummaryTag(local));
      }
    });
    CallGraph graph = Scene.v().getCallGraph();
    assertTrue("The ordinary API target must remain", hasTarget(graph, invocation, api));
    assertTrue("The local summary must discover its callback", hasTarget(graph, invocation, target));
    assertEquals(Collections.singleton(target.getDeclaringClass().getType()),
        Scene.v().getPointsToAnalysis().reachingObjects(targetBody.getThisLocal()).possibleTypes());
    assertEquals(Collections.singleton(payload.getType()),
        Scene.v().getPointsToAnalysis().reachingObjects(targetBody.getParameterLocal(0)).possibleTypes());
  }

  @Test
  public void covariantCallbackReturnDoesNotDiscardItsParameterMappings() {
    SootClass contract = new SootClass("example.CovariantCallback", Modifier.PUBLIC | Modifier.INTERFACE | Modifier.ABSTRACT);
    Scene.v().addClass(contract);
    contract.setLibraryClass();
    SootMethod declared = new SootMethod("accept", Collections.singletonList(payload.getType()),
        RefType.v("java.lang.Object"), Modifier.PUBLIC | Modifier.ABSTRACT);
    contract.addMethod(declared);
    SootClass taskClass = newClass("example.CovariantTask", true);
    taskClass.addInterface(contract);
    SootMethod target = new SootMethod("accept", declared.getParameterTypes(), RefType.v("java.lang.String"), Modifier.PUBLIC);
    taskClass.addMethod(target);
    Body targetBody = instanceBody(target, true);
    targetBody.getUnits().add(Jimple.v().newReturnStmt(NullConstant.v()));
    SootMethod api = new SootMethod("scheduleCovariant", Arrays.asList(contract.getType(), payload.getType()), VoidType.v(),
        Modifier.PUBLIC | Modifier.NATIVE);
    dispatcher.addMethod(api);
    SootClass app = newClass("example.CovariantApplication", true);
    SootMethod entry = new SootMethod("entry", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
    app.addMethod(entry);
    Body body = Jimple.v().newBody(entry);
    entry.setActiveBody(body);
    Local receiver = allocate(body, "dispatcher", dispatcher);
    Local task = allocate(body, "task", taskClass);
    Local value = allocate(body, "payload", payload);
    Stmt invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(receiver, api.makeRef(), task, value));
    body.getUnits().add(invocation);
    body.getUnits().add(Jimple.v().newReturnVoidStmt());
    DirectTarget direct = new DirectTarget(contract.getType(), new MethodSubSignature(declared.makeRef()), 0);
    direct.getParameterMappings().add(new DirectParameterMapping(1, 0));
    VirtualEdge local = new VirtualEdge(Kind.GENERIC_FAKE, new InstanceinvokeSource(invocation), direct);
    runWithCustomBuilder(entry, method -> {
      if (method == entry) {
        invocation.addTag(new VirtualEdgeSummaryTag(local));
      }
    });
    assertTrue("The callback must use Soot's resolved covariant target", hasTarget(Scene.v().getCallGraph(), invocation, target));
    assertEquals(Collections.singleton(taskClass.getType()),
        Scene.v().getPointsToAnalysis().reachingObjects(targetBody.getThisLocal()).possibleTypes());
    assertEquals(Collections.singleton(payload.getType()),
        Scene.v().getPointsToAnalysis().reachingObjects(targetBody.getParameterLocal(0)).possibleTypes());
  }

  @Test
  public void argumentMappingsAndNewObjectsPropagateThroughNestedCallbacks() {
    assertNestedCallbacks(false, false);
  }

  @Test
  public void coldCallbackBodyUsesTheSameFirstReachablePreparation() {
    assertNestedCallbacks(true, false);
  }

  @Test
  public void callbackBodyGeneratedDuringConstructionUsesTheSamePreparation() {
    assertNestedCallbacks(false, true);
  }

  private void assertNestedCallbacks(boolean cold, boolean generated) {
    SootMethod touch = new SootMethod("touch", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC);
    payload.addMethod(touch);
    Body touchBody = instanceBody(touch, false);
    touchBody.getUnits().add(Jimple.v().newReturnVoidStmt());

    SootMethod nested = callbackMethod("example.NestedTask");
    Body nestedBody = instanceBody(nested, true);
    nestedBody.getUnits().add(Jimple.v().newInvokeStmt(
        Jimple.v().newVirtualInvokeExpr(nestedBody.getParameterLocal(0), touch.makeRef())));
    nestedBody.getUnits().add(Jimple.v().newReturnVoidStmt());

    SootMethod outer = callbackMethod("example.OuterTask");
    Body outerBody = instanceBody(outer, true);
    Local nestedReceiver = allocate(outerBody, "nestedDispatcher", dispatcher);
    Local nestedTask = allocate(outerBody, "nestedTask", nested.getDeclaringClass());
    Stmt nestedCall = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(nestedReceiver, schedule.makeRef(),
        nestedTask, outerBody.getParameterLocal(0)));
    outerBody.getUnits().add(nestedCall);
    outerBody.getUnits().add(Jimple.v().newReturnVoidStmt());
    if (cold) {
      outer.setSource((method, phase) -> outerBody);
      outer.releaseActiveBody();
      assertFalse(outer.hasActiveBody());
    }
    if (generated) {
      nested.releaseActiveBody();
      assertFalse(nested.hasActiveBody());
    }

    SootMethod ignored = callbackMethod("example.IgnoredTask");
    Body ignoredBody = instanceBody(ignored, true);
    ignoredBody.getUnits().add(Jimple.v().newReturnVoidStmt());
    SootClass ignoredPayload = newClass("example.IgnoredPayload", true);
    ignoredPayload.setSuperclass(payload);

    SootClass app = newClass("example.Application", true);
    SootMethod entry = new SootMethod("entry", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
    app.addMethod(entry);
    Body entryBody = Jimple.v().newBody(entry);
    entry.setActiveBody(entryBody);
    Local api = allocate(entryBody, "dispatcher", dispatcher);
    Local task = allocate(entryBody, "outerTask", outer.getDeclaringClass());
    Local value = allocate(entryBody, "payload", payload);
    Stmt selectedCall = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(api, schedule.makeRef(), task, value));
    entryBody.getUnits().add(selectedCall);
    Local otherTask = allocate(entryBody, "ignoredTask", ignored.getDeclaringClass());
    Local otherValue = allocate(entryBody, "ignoredPayload", ignoredPayload);
    Stmt otherCall = Jimple.v().newInvokeStmt(
        Jimple.v().newVirtualInvokeExpr(api, schedule.makeRef(), otherTask, otherValue));
    entryBody.getUnits().add(otherCall);
    entryBody.getUnits().add(Jimple.v().newReturnVoidStmt());

    Map<SootMethod, Integer> preparations = new HashMap<>();
    Consumer<SootMethod> prepare = method -> {
      preparations.merge(method, 1, Integer::sum);
      if (method == entry) {
        selectedCall.addTag(new VirtualEdgeSummaryTag(summary(selectedCall)));
      } else if (method == outer) {
        if (generated) {
          nested.setActiveBody(nestedBody);
        }
        nestedCall.addTag(new VirtualEdgeSummaryTag(summary(nestedCall)));
      }
    };
    runWithCustomBuilder(entry, prepare);

    CallGraph graph = Scene.v().getCallGraph();
    assertTrue("The selected call must invoke its callback", hasTarget(graph, selectedCall, outer));
    assertTrue("Newly reachable callback bodies must receive models", hasTarget(graph, nestedCall, nested));
    assertFalse("A local summary must not create callbacks at the other call", hasTarget(graph, otherCall, ignored));
    assertTrue("The ordinary native API target must remain", hasTarget(graph, selectedCall, schedule));
    assertTrue("The mapped payload must resolve its later virtual call", graph.edgesInto(touch).hasNext());
    assertEquals(Collections.singleton(outer.getDeclaringClass().getType()),
        Scene.v().getPointsToAnalysis().reachingObjects(outerBody.getThisLocal()).possibleTypes());
    assertEquals(Collections.singleton(payload.getType()),
        Scene.v().getPointsToAnalysis().reachingObjects(nestedBody.getParameterLocal(0)).possibleTypes());
    assertEquals(Integer.valueOf(1), preparations.get(entry));
    assertEquals(Integer.valueOf(1), preparations.get(outer));
    assertEquals(Integer.valueOf(1), preparations.get(nested));
    assertTrue(dispatcher.isLibraryClass());
    G.reset();
    assertFalse(Scene.v().hasCallGraph());
    assertSame(SparkTransformer.v(), PackManager.v().getPack("cg").get("cg.spark").getTransformer());
  }

  @Test
  public void demandStaticSingleKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.STATIC, "single");
  }

  @Test
  public void demandStaticCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.STATIC, "covariant");
  }

  @Test
  public void demandStaticCallbackPolyKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.STATIC, "callbackPoly");
  }

  @Test
  public void demandStaticCallbackPolyCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.STATIC, "callbackPolyCovariant");
  }

  @Test
  public void demandVirtualSingleKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.VIRTUAL, "single");
  }

  @Test
  public void demandVirtualCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.VIRTUAL, "covariant");
  }

  @Test
  public void demandVirtualCallbackPolyKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.VIRTUAL, "callbackPoly");
  }

  @Test
  public void demandVirtualCallbackPolyCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.VIRTUAL, "callbackPolyCovariant");
  }

  @Test
  public void demandInterfaceSingleKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.INTERFACE, "single");
  }

  @Test
  public void demandInterfaceCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.INTERFACE, "covariant");
  }

  @Test
  public void demandInterfaceCallbackPolyKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.INTERFACE, "callbackPoly");
  }

  @Test
  public void demandInterfaceCallbackPolyCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.INTERFACE, "callbackPolyCovariant");
  }

  @Test
  public void demandSpecialSingleKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.SPECIAL, "single");
  }

  @Test
  public void demandSpecialCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.SPECIAL, "covariant");
  }

  @Test
  public void demandSpecialCallbackPolyKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.SPECIAL, "callbackPoly");
  }

  @Test
  public void demandSpecialCallbackPolyCovariantKeepsCallbackContext() {
    assertDemandPropagation(InvocationKind.SPECIAL, "callbackPolyCovariant");
  }

  @Test
  public void demandVirtualPolymorphicApiKeepsItsOwnReceiver() {
    assertDemandPropagation(InvocationKind.VIRTUAL, "apiPoly");
  }

  @Test
  public void demandInterfacePolymorphicApiKeepsItsOwnReceiver() {
    assertDemandPropagation(InvocationKind.INTERFACE, "apiPoly");
  }

  private void assertDemandPropagation(InvocationKind kind, String shape) {
    boolean covariant = shape.contains("ovariant");
    boolean callbackPoly = shape.startsWith("callbackPoly");
    SootMethod declaration = callback.getMethodByName("accept");
    if (covariant) {
      declaration.setReturnType(RefType.v("java.lang.Object"));
    }
    SootMethod target = callbackMethod("example.DemandTask");
    if (covariant) {
      target.setReturnType(RefType.v("java.lang.String"));
    }
    SootField field = new SootField("saved", payload.getType(), Modifier.PUBLIC);
    target.getDeclaringClass().addField(field);
    savePayload(target, field, covariant);
    SootClass apiType = dispatcher;
    SootMethod api = schedule;
    if (kind == InvocationKind.STATIC) {
      api = new SootMethod("scheduleStatic", schedule.getParameterTypes(), VoidType.v(),
          Modifier.PUBLIC | Modifier.STATIC | Modifier.NATIVE);
      dispatcher.addMethod(api);
    } else if (kind == InvocationKind.INTERFACE) {
      apiType = new SootClass("example.ApiContract", Modifier.PUBLIC | Modifier.INTERFACE | Modifier.ABSTRACT);
      Scene.v().addClass(apiType);
      apiType.setLibraryClass();
      api = new SootMethod("schedule", schedule.getParameterTypes(), VoidType.v(), Modifier.PUBLIC | Modifier.ABSTRACT);
      apiType.addMethod(api);
      dispatcher.addInterface(apiType);
    }
    SootClass app = newClass("example.DemandApplication", true);
    if (kind == InvocationKind.SPECIAL) {
      app.setSuperclass(dispatcher);
    }
    SootMethod entry = new SootMethod("entry", Collections.emptyList(), VoidType.v(),
        Modifier.PUBLIC | (kind == InvocationKind.SPECIAL ? 0 : Modifier.STATIC));
    app.addMethod(entry);
    Body body = kind == InvocationKind.SPECIAL ? instanceBody(entry, false) : Jimple.v().newBody(entry);
    entry.setActiveBody(body);
    Local base = null;
    if (kind == InvocationKind.SPECIAL) {
      base = body.getThisLocal();
    } else if (kind != InvocationKind.STATIC) {
      base = Jimple.v().newLocal("api", apiType.getType());
      body.getLocals().add(base);
      if (shape.equals("apiPoly")) {
        for (int i = 0; i < 2; i++) {
          SootClass impl = newClass("example.DispatcherImpl" + i, true);
          impl.setSuperclass(dispatcher);
          impl.addMethod(new SootMethod("schedule", api.getParameterTypes(), VoidType.v(), Modifier.PUBLIC | Modifier.NATIVE));
          Local concrete = allocate(body, "api" + i, impl);
          body.getUnits().add(Jimple.v().newAssignStmt(base, concrete));
        }
      } else {
        Local concrete = allocate(body, "concreteApi", dispatcher);
        body.getUnits().add(Jimple.v().newAssignStmt(base, concrete));
      }
    }
    Local first = allocate(body, "firstTask", target.getDeclaringClass());
    Local second = allocate(body, "secondTask", target.getDeclaringClass());
    Local firstValue = allocate(body, "firstValue", payload);
    Local secondValue = allocate(body, "secondValue", payload);
    Local dispatchTask = first;
    SootMethod otherTarget = null;
    if (callbackPoly) {
      SootClass other = newClass("example.OtherTask", true);
      other.setSuperclass(target.getDeclaringClass());
      otherTarget = new SootMethod("accept", target.getParameterTypes(), target.getReturnType(), Modifier.PUBLIC);
      other.addMethod(otherTarget);
      savePayload(otherTarget, field, covariant);
      Local concrete = allocate(body, "otherTask", other);
      dispatchTask = Jimple.v().newLocal("dispatchTask", callback.getType());
      body.getLocals().add(dispatchTask);
      body.getUnits().add(Jimple.v().newAssignStmt(dispatchTask, first));
      body.getUnits().add(Jimple.v().newAssignStmt(dispatchTask, concrete));
    }
    Stmt firstCall = invocation(kind, base, api, dispatchTask, firstValue);
    Stmt secondCall = invocation(kind, base, api, second, secondValue);
    body.getUnits().add(firstCall);
    body.getUnits().add(secondCall);
    Local result = Jimple.v().newLocal("result", payload.getType());
    body.getLocals().add(result);
    body.getUnits().add(Jimple.v().newAssignStmt(result, Jimple.v().newInstanceFieldRef(first, field.makeRef())));
    body.getUnits().add(Jimple.v().newReturnVoidStmt());
    DirectTarget direct = new DirectTarget(callback.getType(), new MethodSubSignature(declaration.makeRef()), 0);
    direct.getParameterMappings().add(new DirectParameterMapping(1, 0));
    VirtualEdge local = new VirtualEdge(Kind.GENERIC_FAKE, null, direct);
    runWithCustomBuilder(entry, method -> {
      if (method == entry) {
        firstCall.addTag(new VirtualEdgeSummaryTag(local));
        secondCall.addTag(new VirtualEdgeSummaryTag(local));
      }
    }, true);
    ContextSensitiveInfo info = new ContextSensitiveInfo(capturedPAG);
    assertEquals("Two model invocations must have distinct contexts", 2, info.getCallSitesInvokingMethod(target).size());
    for (Integer id : info.getCallSitesInvokingMethod(target)) {
      assertSame("Retain the contract declaration, including covariant returns", declaration, info.getInvokedMethod(id));
      assertSame(entry, info.getInvokingMethod(id));
      assertTrue(info.isVirtCall(id));
    }
    if (callbackPoly) {
      Integer id = info.getCallSitesInvokingMethod(otherTarget).iterator().next();
      assertEquals("One logical invocation can dispatch to multiple implementations", 2, info.getCallSiteTargets(id).size());
    }
    if (shape.equals("apiPoly")) {
      int ordinary = 0;
      for (Integer id : info.getCallSitesInMethod(entry)) {
        if (info.getInvokedMethod(id) == api) {
          ordinary++;
          boolean recordedReceiver = capturedPAG.virtualCallsToReceivers.containsKey(firstCall.getInvokeExpr());
          assertEquals("Keep the ordinary API's existing receiver metadata", recordedReceiver, info.isVirtCall(id));
          if (recordedReceiver) {
            assertSame(capturedPAG.findLocalVarNode(base), info.getReceiverForVirtCallSite(id));
          }
          assertEquals(2, info.getCallSiteTargets(id).size());
        }
      }
      assertEquals("The ordinary API keeps its own identities", 2, ordinary);
    }
    DemandCSPointsTo demand = (DemandCSPointsTo) Scene.v().getPointsToAnalysis();
    assertTrue("Use the normal call graph refinement", demand.isRefineCallGraph());
    PointsToSet values = demand.reachingObjects(result);
    assertTrue("Keep the selected callback payload", values.hasNonEmptyIntersection(demand.reachingObjects(firstValue)));
    assertFalse("Exclude the other callback object's payload", values.hasNonEmptyIntersection(demand.reachingObjects(secondValue)));
    assertTrue(hasTarget(Scene.v().getCallGraph(), firstCall, target));
  }

  @Test
  public void demandArrayReceiverWithoutAnExplicitTargetTypeUsesTheObjectDeclaration() {
    SootMethod clone = Scene.v().getObjectType().getSootClass().getMethod("java.lang.Object clone()");
    ArrayType arrayType = ArrayType.v(payload.getType(), 1);
    SootClass app = newClass("example.ArrayApplication", true);
    SootMethod entry = new SootMethod("entry", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
    app.addMethod(entry);
    Body body = Jimple.v().newBody(entry);
    entry.setActiveBody(body);
    Local array = Jimple.v().newLocal("array", arrayType);
    body.getLocals().add(array);
    body.getUnits().add(Jimple.v().newAssignStmt(array, Jimple.v().newNewArrayExpr(payload.getType(), IntConstant.v(1))));
    Stmt invocation = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(array, clone.makeRef()));
    body.getUnits().add(invocation);
    body.getUnits().add(Jimple.v().newReturnVoidStmt());
    VirtualEdge summary = new VirtualEdge(Kind.GENERIC_FAKE, null,
        new DirectTarget(null, new MethodSubSignature(clone.makeRef())));
    runWithCustomBuilder(entry, method -> {
      if (method == entry) invocation.addTag(new VirtualEdgeSummaryTag(summary));
    }, true);
    assertTrue(hasTarget(Scene.v().getCallGraph(), invocation, clone));
    ContextSensitiveInfo info = new ContextSensitiveInfo(capturedPAG);
    assertEquals("Keep both identities even when the API and callback declarations coincide", 2,
        info.getCallSitesInvokingMethod(clone).size());
    int modelCalls = 0;
    for (Integer callSite : info.getCallSitesInvokingMethod(clone)) {
      assertSame(clone, info.getInvokedMethod(callSite));
      if (info.isVirtCall(callSite)) {
        modelCalls++;
        assertSame(capturedPAG.findLocalVarNode(array), info.getReceiverForVirtCallSite(callSite));
      }
    }
    assertEquals(1, modelCalls);
  }

  private static void savePayload(SootMethod method, SootField field, boolean covariant) {
    Body body = instanceBody(method, true);
    body.getUnits().add(Jimple.v().newAssignStmt(Jimple.v().newInstanceFieldRef(body.getThisLocal(), field.makeRef()),
        body.getParameterLocal(0)));
    body.getUnits().add(covariant ? Jimple.v().newReturnStmt(NullConstant.v()) : Jimple.v().newReturnVoidStmt());
  }

  private static Stmt invocation(InvocationKind kind, Local base, SootMethod api, Local task, Local value) {
    if (kind == InvocationKind.STATIC) {
      return Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(api.makeRef(), task, value));
    } else if (kind == InvocationKind.SPECIAL) {
      return Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(base, api.makeRef(), task, value));
    } else if (kind == InvocationKind.INTERFACE) {
      return Jimple.v().newInvokeStmt(Jimple.v().newInterfaceInvokeExpr(base, api.makeRef(), task, value));
    }
    return Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(base, api.makeRef(), task, value));
  }

  @Test
  public void suppressionOnlyDisablesTheSelectedStatementDuringConstruction() {
    SootMethod target = callbackMethod("example.DefaultTask");
    Body callbackBody = instanceBody(target, true);
    callbackBody.getUnits().add(Jimple.v().newReturnVoidStmt());
    SootClass app = newClass("example.SuppressionApplication", true);
    SootMethod entry = new SootMethod("entry", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
    app.addMethod(entry);
    Body body = Jimple.v().newBody(entry);
    entry.setActiveBody(body);
    Local receiver = allocate(body, "dispatcher", dispatcher);
    Local suppressed = allocate(body, "suppressed", target.getDeclaringClass());
    Local retained = allocate(body, "retained", target.getDeclaringClass());
    Local value = allocate(body, "value", payload);
    Stmt first = invocation(InvocationKind.VIRTUAL, receiver, schedule, suppressed, value);
    Stmt second = invocation(InvocationKind.VIRTUAL, receiver, schedule, retained, value);
    body.getUnits().add(first);
    body.getUnits().add(second);
    body.getUnits().add(Jimple.v().newReturnVoidStmt());
    defaultSummaries = new VirtualEdgesSummaries(Collections.singletonList(summary(second)));
    runWithCustomBuilder(entry, method -> {
      if (method == entry) {
        first.addTag(new VirtualEdgeSummaryTag(null));
      }
    });
    assertTrue(hasTarget(Scene.v().getCallGraph(), first, schedule));
    assertTrue(hasTarget(Scene.v().getCallGraph(), second, schedule));
    assertFalse(hasTarget(Scene.v().getCallGraph(), first, target));
    assertTrue(hasTarget(Scene.v().getCallGraph(), second, target));
    assertTrue(Scene.v().getPointsToAnalysis().reachingObjects(callbackBody.getThisLocal())
        .hasNonEmptyIntersection(Scene.v().getPointsToAnalysis().reachingObjects(retained)));
    assertFalse(Scene.v().getPointsToAnalysis().reachingObjects(callbackBody.getThisLocal())
        .hasNonEmptyIntersection(Scene.v().getPointsToAnalysis().reachingObjects(suppressed)));
  }

  private static void runWithCustomBuilder(SootMethod entry, Consumer<SootMethod> prepare) {
    runWithCustomBuilder(entry, prepare, false);
  }

  private static void runWithCustomBuilder(SootMethod entry, Consumer<SootMethod> prepare, boolean demand) {
    Scene.v().setEntryPoints(Collections.singletonList(entry));
    Map<String, String> options = new HashMap<>(PhaseOptions.v().getPhaseOptions("cg.spark"));
    options.put("enabled", "true");
    options.put("on-fly-cg", "true");
    options.put("simulate-natives", "false");
    options.put("cs-demand", Boolean.toString(demand));
    options.put("lazy-pts", "false");
    options.put("traversal", "1000000");
    options.put("passes", "10");
    PackManager.v().getPack("cg").remove("cg.spark");
    PackManager.v().getPack("cg").add(new Transform("cg.spark", new ModelingSparkTransformer(prepare)));
    options.forEach((key, value) -> assertTrue(PhaseOptions.v().setPhaseOption("cg.spark", key + ":" + value)));
    PackManager.v().getPack("cg").apply();
  }

  private static class ModelingSparkTransformer extends SparkTransformer {
    private final Consumer<SootMethod> prepare;

    private ModelingSparkTransformer(Consumer<SootMethod> prepare) {
      this.prepare = prepare;
    }

    @Override
    protected void propagatePAG(soot.options.SparkOptions opts, PAG pag) {
      capturedPAG = pag;
      super.propagatePAG(opts, pag);
    }

    @Override
    protected ContextInsensitiveBuilder createContextInsensitiveBuilder() {
      return new ModelingPAGBuilder(prepare);
    }
  }

  private static class ModelingPAGBuilder extends ContextInsensitiveBuilder {
    private final Consumer<SootMethod> prepare;

    private ModelingPAGBuilder(Consumer<SootMethod> prepare) {
      this.prepare = prepare;
    }

    @Override
    protected OnFlyCallGraph createOnFlyCallGraph(PAG pag, boolean appOnly) {
      Consumer<SootMethod> prepareMethod = prepare;
      return new OnFlyCallGraph(pag, appOnly) {
        @Override
        protected OnFlyCallGraphBuilder createOnFlyCallGraphBuilder(ContextManager cm, ReachableMethods reachable,
            boolean onlyApplication) {
          return new ModelingCallGraphBuilder(cm, reachable, onlyApplication, prepareMethod);
        }
      };
    }
  }

  private static class ModelingCallGraphBuilder extends OnFlyCallGraphBuilder {
    private final Consumer<SootMethod> prepare;

    private ModelingCallGraphBuilder(ContextManager cm, ReachableMethods reachable, boolean appOnly,
        Consumer<SootMethod> prepare) {
      super(cm, reachable, appOnly);
      this.prepare = prepare;
    }

    @Override
    protected VirtualEdgesSummaries initializeEdgeSummaries() {
      return defaultSummaries == null ? super.initializeEdgeSummaries() : defaultSummaries;
    }

    @Override
    protected void processNewMethod(SootMethod method) {
      if (method.isConcrete()) {
        method.retrieveActiveBody();
        prepare.accept(method);
      }
      super.processNewMethod(method);
    }
  }

  private VirtualEdge summary(Stmt statement) {
    DirectTarget target = new DirectTarget(callback.getType(),
        new MethodSubSignature(callback.getMethod("void accept(example.Payload)").makeRef()), 0);
    target.getParameterMappings().add(new DirectParameterMapping(1, 0));
    return new VirtualEdge(Kind.GENERIC_FAKE, new InstanceinvokeSource(statement), target);
  }

  private static SootClass newClass(String name, boolean application) {
    SootClass type = new SootClass(name, Modifier.PUBLIC);
    type.setSuperclass(Scene.v().getSootClass("java.lang.Object"));
    Scene.v().addClass(type);
    if (application) {
      type.setApplicationClass();
    } else {
      type.setLibraryClass();
    }
    return type;
  }

  private SootMethod callbackMethod(String name) {
    SootClass type = newClass(name, true);
    type.addInterface(callback);
    SootMethod method = new SootMethod("accept", Collections.singletonList(payload.getType()), VoidType.v(), Modifier.PUBLIC);
    type.addMethod(method);
    return method;
  }

  private static Body instanceBody(SootMethod method, boolean parameter) {
    Body body = Jimple.v().newBody(method);
    method.setActiveBody(body);
    Local receiver = Jimple.v().newLocal("this", method.getDeclaringClass().getType());
    body.getLocals().add(receiver);
    body.getUnits().add(Jimple.v().newIdentityStmt(receiver, Jimple.v().newThisRef(method.getDeclaringClass().getType())));
    if (parameter) {
      Local value = Jimple.v().newLocal("payload", method.getParameterType(0));
      body.getLocals().add(value);
      body.getUnits().add(Jimple.v().newIdentityStmt(value, Jimple.v().newParameterRef(method.getParameterType(0), 0)));
    }
    return body;
  }

  private static Local allocate(Body body, String name, SootClass type) {
    Local local = Jimple.v().newLocal(name, type.getType());
    body.getLocals().add(local);
    body.getUnits().add(Jimple.v().newAssignStmt(local, Jimple.v().newNewExpr(type.getType())));
    return local;
  }

  private static boolean hasTarget(CallGraph graph, Stmt statement, SootMethod target) {
    Iterator<Edge> edges = graph.edgesOutOf(statement);
    while (edges.hasNext()) {
      if (edges.next().tgt() == target) {
        return true;
      }
    }
    return false;
  }
}
