package com.skala.helpdesk.web;

public class CharacterNotFoundException extends RuntimeException {

    public CharacterNotFoundException(String characterId) {
        super("캐릭터를 찾을 수 없습니다: " + characterId);
    }
}
