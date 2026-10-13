/*
 * *****************************************************************************
 * Copyright (C) 2026 Ryan Lovett
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.console.activity;

/**
 * Radio (talker) recently heard on a console card.
 *
 * @param radioId formatted radio identifier
 * @param radioName alias name of the radio, or null when the radio is not aliased
 * @param timestamp when the radio was last heard on the card, milliseconds since epoch
 */
public record RecentTalker(String radioId, String radioName, long timestamp)
{
}
