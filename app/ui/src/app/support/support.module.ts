import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActionModule, ListModule } from 'patternfly-ng';
import { TooltipModule } from 'ngx-bootstrap/tooltip';

import { SyndesisCommonModule } from '../common/common.module';
import { SupportComponent } from './support.component';

import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterModule, Routes } from '@angular/router';
import { DynamicFormsCoreModule } from '@ng-dynamic-forms/core';
import { PatternflyUIModule } from '../common/ui-patternfly/ui-patternfly.module';

@NgModule({
  imports: [
    ActionModule,
    CommonModule,
    DynamicFormsCoreModule,
    FormsModule,
    ListModule,
    PatternflyUIModule,
    ReactiveFormsModule,
    SyndesisCommonModule,
    TooltipModule,
  ],
  declarations: [SupportComponent],
  exports: [SupportComponent],
})
export class SupportModule {}
