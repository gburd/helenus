/*
 *      Copyright (C) 2015 The Casser Authors
 *      Copyright (C) 2015-2018 The Helenus Authors
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package net.helenus.mapping.validator;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import net.helenus.mapping.annotation.Constraints;

public final class DistinctValidator
    extends AbstractConstraintValidator<Constraints.Distinct, CharSequence>
    implements ConstraintValidator<Constraints.Distinct, CharSequence> {

  private Constraints.Distinct annotation;

  @Override
  public void initialize(Constraints.Distinct constraintAnnotation) {
    super.initialize(constraintAnnotation);
    this.annotation = constraintAnnotation;
  }

  @Override
  public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
    // TODO(gburd): check that the list contains valid property names.
    return true;
  }

  public String[] value() {
    return annotation == null ? null : annotation.value();
  }

  public boolean alone() {
    return annotation == null ? true : annotation.alone();
  }

  public boolean combined() {
    return annotation == null ? true : annotation.combined();
  }
}
