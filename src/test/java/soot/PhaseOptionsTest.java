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

import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class PhaseOptionsTest {
  @Before
  public void reset() {
    G.reset();
  }

  @Test
  public void clearingTemporaryOptionsRestoresDefaultsWithoutChangingAnotherInstance() {
    Transform temporary = phase();
    Transform retained = phase();
    PhaseOptions options = PhaseOptions.v();
    options.setPhaseOption(temporary, "value:temporary");
    options.setPhaseOption(retained, "value:retained");
    Map<String, String> snapshot = options.getPhaseOptions(temporary);
    options.clearPhaseOptions(temporary);
    assertEquals("default", options.getPhaseOptions(temporary).get("value"));
    assertEquals("retained", options.getPhaseOptions(retained).get("value"));
    assertEquals("temporary", snapshot.get("value"));
    options.clearPhaseOptions(temporary);
    assertEquals("default", options.getPhaseOptions(temporary).get("value"));
  }

  private static Transform phase() {
    Transform phase = new Transform("jtp.temporary", new SceneTransformer() {
      @Override
      protected void internalTransform(String name, Map<String, String> options) {
      }
    });
    phase.setDeclaredOptions("enabled value");
    phase.setDefaultOptions("enabled:false value:default");
    return phase;
  }
}
