/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.computation.component;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.assertj.core.api.Assertions.assertThat;

public class DbIdsRepositoryTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  Component component = DumbComponent.DUMB_PROJECT;

  @Test
  public void add_and_get_component() throws Exception {
    DbIdsRepository cache = new DbIdsRepository();
    cache.setComponentId(component, 10L);

    assertThat(cache.getComponentId(component)).isEqualTo(10L);
  }

  @Test
  public void fail_on_unknown_ref() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Component ref '" + component.getRef() + "' has no component id");

    new DbIdsRepository().getComponentId(DumbComponent.DUMB_PROJECT);
  }

  @Test
  public void fail_if_component_id_already_set() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Component ref '" + component.getRef() + "' has already a component id");

    DbIdsRepository cache = new DbIdsRepository();
    cache.setComponentId(component, 10L);
    cache.setComponentId(component, 11L);
  }

}
