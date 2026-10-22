package com.example.myapplication;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import java.util.UUID;

/** Explicitly carry the source PDF's password and permissions into a newly assembled copy. */
final class PdfProtection {
    static void inheritAll(java.util.List<PDDocument> sources,PDDocument target,String password)throws java.io.IOException {
        boolean encrypted=false;
        com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission combined=new com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission();
        for(PDDocument source:sources){
            encrypted|=source.isEncrypted();
            com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission p=source.getCurrentAccessPermission();
            combined.setCanPrint(combined.canPrint()&&p.canPrint());
            combined.setCanPrintDegraded(combined.canPrintDegraded()&&p.canPrintDegraded());
            combined.setCanModify(combined.canModify()&&p.canModify());
            combined.setCanModifyAnnotations(combined.canModifyAnnotations()&&p.canModifyAnnotations());
            combined.setCanExtractContent(combined.canExtractContent()&&p.canExtractContent());
            combined.setCanExtractForAccessibility(combined.canExtractForAccessibility()&&p.canExtractForAccessibility());
            combined.setCanFillInForm(combined.canFillInForm()&&p.canFillInForm());
            combined.setCanAssembleDocument(combined.canAssembleDocument()&&p.canAssembleDocument());
        }
        if(!encrypted)return;
        StandardProtectionPolicy policy=new StandardProtectionPolicy(UUID.randomUUID().toString(),password==null?"":password,combined);
        policy.setEncryptionKeyLength(128);policy.setPreferAES(true);
        target.setAllSecurityToBeRemoved(false);target.protect(policy);
    }
    static void inherit(PDDocument source,PDDocument target,String password)throws java.io.IOException {
        if(!source.isEncrypted())return;
        StandardProtectionPolicy policy=new StandardProtectionPolicy(UUID.randomUUID().toString(),password==null?"":password,source.getCurrentAccessPermission());
        policy.setEncryptionKeyLength(128);policy.setPreferAES(true);
        target.setAllSecurityToBeRemoved(false);target.protect(policy);
    }
}
