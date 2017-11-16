import { 
  ApplicationRef,
  Component,
  Input,
  ViewChild,
  OnInit,
  OnDestroy,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ModalDirective } from 'ngx-bootstrap/modal';

import { Subscription } from 'rxjs/Subscription';

import {
  Action,
  ActionConfig,
  ListConfig,
  ListEvent,
  EmptyStateConfig,
  Notification,
  NotificationService,
  NotificationType,
} from 'patternfly-ng';

import { Integrations, Integration } from '../model';
import { IntegrationStore } from '../store/integration/integration.store';
import { IntegrationSupportService } from '../store/integration-support.service';
import { ModalService } from '../common/modal/modal.service';
import { log, getCategory } from '../logging';
import { DynamicFormControlModel, DynamicFormService } from '@ng-dynamic-forms/core';
import { FormFactoryService } from '../common/forms.service';
import { FormGroup } from '@angular/forms';

import fileSaver = require("file-saver");

const SUPPORT_FORM_CONFIG = {
  platformLogs: {
    displayName: 'Platform Logs',
    type: 'boolean',
    description: 'Include logs of platform services',
    default: false
  },
  integrationLogs: {
    displayName: 'Integrations Logs',
    type: 'boolean',
    description: 'Include logs of integrations',
  }
};

@Component({
  selector: 'syndesis-support-page',
  templateUrl: './support.component.html',
  styleUrls: ['./support.component.scss'],
})
export class SupportComponent  implements OnInit, OnDestroy {

  formModel: DynamicFormControlModel[] = undefined;
  formGroup: FormGroup = undefined;
  formConfig: any;
  
  constructor(
    public store: IntegrationStore,
    public route: ActivatedRoute,
    public router: Router,
    public notificationService: NotificationService,
    public modalService: ModalService,
    public application: ApplicationRef,
    public integrationSupportService: IntegrationSupportService,
    public formFactory: FormFactoryService,
    public formService: DynamicFormService
  ) {}

  getPods() {
    this.integrationSupportService
      .getPods()
      .toPromise()
      .then((resp: any) => {
        const body = JSON.parse(resp['_body']);
        console.log('++++++++++++++++++++' + body );
      });
  }

  loadForm() {
    //this.formConfig = SUPPORT_FORM_CONFIG;
    this.integrationSupportService
      .getSupportFormConfiguration()
      .toPromise()
      .then((resp: any) => {
        this.formConfig = JSON.parse(resp['_body']);

        this.formModel = this.formFactory.createFormModel( this.formConfig, {} );
        // eventually customize form
        // this.formModel
        // .filter(model => model instanceof DynamicInputModel)
        // .forEach(model => ((<DynamicInputModel>model).readOnly = readOnly));
        this.formGroup = this.formService.createFormGroup(this.formModel);
      });

  }

  download() {
    this.buildData();
  }

  close(id: string) {
    this.modalService.hide(id, false);
  }

  ngOnInit() {
    this.loadForm();
  }

  ngOnDestroy() {
    // not sure if i need this yet
  }

  buildData(data: any = {}) {
    const formValue = this.formGroup ? this.formGroup.value : {};
    console.log(formValue);
    this.integrationSupportService
    .downloadSupportData(formValue)
    .map(res => res.blob())
    .subscribe(response => {  
      fileSaver.saveAs(response, "syndesis.zip");
    },
    error => console.log("Error downloading the file."),
    ()    => this.close('supportPage')
  );
    return {  };
  }
}
