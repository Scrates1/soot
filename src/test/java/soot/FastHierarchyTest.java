package soot;

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
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import soot.options.Options;

public class FastHierarchyTest {
  private SootClass contract;
  private SootClass otherContract;
  private SootMethod contractMethod;

  @Before
  public void setUp() {
    G.reset();
    Options.v().set_allow_phantom_refs(true);
    Options.v().set_prepend_classpath(true);
    Scene.v().loadBasicClasses();
    contract = newInterface("example.Contract");
    contractMethod = new SootMethod("run", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.ABSTRACT);
    contract.addMethod(contractMethod);
    otherContract = newInterface("example.OtherContract");
    otherContract.addMethod(new SootMethod("run", Collections.emptyList(), VoidType.v(),
        Modifier.PUBLIC | Modifier.ABSTRACT));
  }

  @Test
  public void anySubTypeOfAnAbstractClassIsStorableInItsOwnInterface() {
    SootClass abstractBase = newClass("example.AbstractBase", Modifier.PUBLIC | Modifier.ABSTRACT);
    abstractBase.addInterface(contract);
    FastHierarchy hierarchy = Scene.v().getOrMakeFastHierarchy();
    assertTrue("Every subtype of the abstract base is also a Contract",
        hierarchy.canStoreType(AnySubType.v(abstractBase.getType()), contract.getType()));
    assertFalse(hierarchy.canStoreType(AnySubType.v(abstractBase.getType()), otherContract.getType()));
  }

  @Test
  public void anySubTypeOfAnAbstractClassStillRequiresAKnownImplementationForAnUnrelatedInterface() {
    SootClass abstractBase = newClass("example.AbstractBase", Modifier.PUBLIC | Modifier.ABSTRACT);
    SootClass concrete = newClass("example.Concrete", Modifier.PUBLIC);
    concrete.setSuperclass(abstractBase);
    concrete.addInterface(contract);
    FastHierarchy hierarchy = Scene.v().getOrMakeFastHierarchy();
    assertTrue(hierarchy.canStoreType(AnySubType.v(abstractBase.getType()), contract.getType()));
    assertFalse(hierarchy.canStoreType(AnySubType.v(abstractBase.getType()), otherContract.getType()));
  }

  @Test
  public void boundedAbstractDispatchKeepsOnlyReceiversSatisfyingBothBounds() {
    SootClass both = newClass("example.Both", Modifier.PUBLIC);
    both.addInterface(contract);
    both.addInterface(otherContract);
    SootMethod bothRun = addRun(both);
    SootClass contractOnly = newClass("example.ContractOnly", Modifier.PUBLIC);
    contractOnly.addInterface(contract);
    addRun(contractOnly);
    SootClass otherOnly = newClass("example.OtherOnly", Modifier.PUBLIC);
    otherOnly.addInterface(otherContract);
    addRun(otherOnly);
    FastHierarchy hierarchy = Scene.v().getOrMakeFastHierarchy();
    Set<SootMethod> targets = hierarchy.resolveAbstractDispatch(contract, otherContract, contractMethod.makeRef());
    assertEquals(Collections.singleton(bothRun), targets);
  }

  @Test
  public void boundedAbstractDispatchRetainsAnImplementationInheritedFromAnAbstractBase() {
    SootClass abstractBase = newClass("example.AbstractBase", Modifier.PUBLIC | Modifier.ABSTRACT);
    abstractBase.addInterface(contract);
    SootMethod inherited = addRun(abstractBase);
    SootClass sibling = newClass("example.Sibling", Modifier.PUBLIC);
    sibling.addInterface(contract);
    addRun(sibling);
    FastHierarchy hierarchy = Scene.v().getOrMakeFastHierarchy();
    assertEquals("An unknown concrete extension inherits the abstract base's implementation",
        Collections.singleton(inherited), hierarchy.resolveAbstractDispatch(contract, abstractBase, contractMethod.makeRef()));
    assertEquals(Collections.singleton(inherited),
        hierarchy.resolveAbstractDispatch(abstractBase, contract, contractMethod.makeRef()));
  }

  @Test
  public void boundedAbstractDispatchUsesTheReceiverClassRatherThanTheMethodOwner() {
    SootClass parent = newClass("example.Parent", Modifier.PUBLIC);
    parent.addInterface(contract);
    SootMethod parentRun = addRun(parent);
    SootClass selected = newClass("example.Selected", Modifier.PUBLIC);
    selected.setSuperclass(parent);
    selected.addInterface(otherContract);
    SootClass unselected = newClass("example.Unselected", Modifier.PUBLIC);
    unselected.setSuperclass(parent);
    FastHierarchy hierarchy = Scene.v().getOrMakeFastHierarchy();
    assertEquals("Selected inherits run() from Parent, which does not implement OtherContract itself",
        Collections.singleton(parentRun), hierarchy.resolveAbstractDispatch(contract, otherContract, contractMethod.makeRef()));
    assertEquals(Collections.emptySet(),
        hierarchy.resolveAbstractDispatch(unselected, otherContract, contractMethod.makeRef()));
  }

  private static SootMethod addRun(SootClass owner) {
    SootMethod run = new SootMethod("run", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC);
    owner.addMethod(run);
    return run;
  }

  private static SootClass newInterface(String name) {
    SootClass type = new SootClass(name, Modifier.PUBLIC | Modifier.INTERFACE | Modifier.ABSTRACT);
    type.setSuperclass(Scene.v().getObjectType().getSootClass());
    Scene.v().addClass(type);
    return type;
  }

  private static SootClass newClass(String name, int modifiers) {
    SootClass type = new SootClass(name, modifiers);
    type.setSuperclass(Scene.v().getObjectType().getSootClass());
    Scene.v().addClass(type);
    type.setApplicationClass();
    return type;
  }
}
