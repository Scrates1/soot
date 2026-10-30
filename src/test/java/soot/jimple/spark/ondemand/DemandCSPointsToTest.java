package soot.jimple.spark.ondemand;

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
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import soot.Body;
import soot.G;
import soot.Local;
import soot.Modifier;
import soot.PackManager;
import soot.PhaseOptions;
import soot.PointsToAnalysis;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.VoidType;
import soot.jimple.Jimple;
import soot.options.Options;

/** Runs the real cg.spark phase with cs-demand enabled and checks the refined results. */
public class DemandCSPointsToTest {

  @Before
  public void setUp() {
    G.reset();
    Options.v().set_allow_phantom_refs(true);
    Options.v().set_prepend_classpath(true);
    Scene.v().loadBasicClasses();
  }

  @Test
  public void refinementStaysWithinThePagResultAcrossACast() {
    SootClass selected = newClass("example.CastSelected");
    SootClass sibling = newClass("example.CastSibling");
    SootClass app = newClass("example.CastApplication");
    SootMethod entry = new SootMethod("entry", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
    app.addMethod(entry);
    Body body = Jimple.v().newBody(entry);
    entry.setActiveBody(body);
    Local first = allocate(body, "selected", selected);
    Local other = allocate(body, "sibling", sibling);
    Local wide = Jimple.v().newLocal("wide", Scene.v().getObjectType());
    Local narrowed = Jimple.v().newLocal("narrowed", selected.getType());
    body.getLocals().add(wide);
    body.getLocals().add(narrowed);
    body.getUnits().add(Jimple.v().newAssignStmt(wide, first));
    body.getUnits().add(Jimple.v().newAssignStmt(wide, other));
    body.getUnits().add(Jimple.v().newAssignStmt(narrowed, Jimple.v().newCastExpr(wide, selected.getType())));
    body.getUnits().add(Jimple.v().newReturnVoidStmt());

    runSparkWithDemand(entry);

    PointsToAnalysis demand = Scene.v().getPointsToAnalysis();
    assertTrue(demand instanceof DemandCSPointsTo);
    assertEquals("SPARK filters the cast by type", Collections.singleton(selected.getType()),
        ((DemandCSPointsTo) demand).getPAG().reachingObjects(narrowed).possibleTypes());
    assertEquals("Refinement must not reintroduce objects the solved PAG excluded",
        Collections.singleton(selected.getType()), demand.reachingObjects(narrowed).possibleTypes());
  }

  private static void runSparkWithDemand(SootMethod entry) {
    Scene.v().setEntryPoints(Collections.singletonList(entry));
    Map<String, String> options = new HashMap<>();
    options.put("enabled", "true");
    options.put("on-fly-cg", "true");
    options.put("simulate-natives", "false");
    options.put("cs-demand", "true");
    options.put("lazy-pts", "false");
    options.forEach((key, value) -> assertTrue(PhaseOptions.v().setPhaseOption("cg.spark", key + ":" + value)));
    PackManager.v().getPack("cg").apply();
  }

  private static SootClass newClass(String name) {
    SootClass type = new SootClass(name, Modifier.PUBLIC);
    type.setSuperclass(Scene.v().getObjectType().getSootClass());
    Scene.v().addClass(type);
    type.setApplicationClass();
    return type;
  }

  private static Local allocate(Body body, String name, SootClass type) {
    Local local = Jimple.v().newLocal(name, type.getType());
    body.getLocals().add(local);
    body.getUnits().add(Jimple.v().newAssignStmt(local, Jimple.v().newNewExpr(type.getType())));
    return local;
  }
}
