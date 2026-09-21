hookahHandler:
  type: world
  debug: false
  events:
    on player right clicks brewing_stand with:air:
        - flag <player.cursor_on> hookah
        - if <context.location.has_flag[hookah]> && <player.is_sneaking||true>:
            - determine passively cancelled
            - if !<player.has_flag[smoking]>:
                - flag <player> smoking:15 expire:5s
            - if <player.flag[smoking]> < 35:
                - flag <player> smoking:+:1 expire:5s
            - else:
                - cast confusion <player> duration:2s
            - playsound at:<player.location> sound:entity_villager_work_cleric pitch:0.1 volume:0.16
            - define possibleEffects <list>
            - foreach <context.location.inventory.list_contents||<list>> as:item:
              - if <[item].effects_data||null> != null && !<[item].effects_data.is_empty>:
                - define possibleEffects:->:<[item].effects_data.first>
            - if !<[possibleEffects].is_empty>:
              - define chosenEffect <[possibleEffects].random>
              - define effectName <[chosenEffect].get[type]>
              - define effectPower <[chosenEffect].get[amplifier]||0>
              - if <server.potion_effect_types.contains[<[effectName]>]>:
                - cast <[effectName]> amplifier:<[effectPower]> <player> duration:3s

            - while <player.cursor_on.has_flag[hookah]||false> || <player.has_flag[hand]>:
                - wait 2s
            - while <player.flag[smoking].add[5]> > 0 && !<player.has_flag[hand]>:
                - if <util.random.int[1].to[20]> != 1:
                    - playeffect effect:poof at:<player.eye_location.below[0.35].forward[0.7]> quantity:1 offset:0 velocity:<player.eye_location.forward[1].above[<util.random.decimal[-0.1].to[0.1]>].left[<util.random.decimal[-0.1].to[0.1]>].sub[<player.eye_location>].mul[0.2]>
                - playsound at:<player.location> sound:entity_phantom_ambient pitch:0.5 volume:0.05
                - flag player smoking:-:1
                - wait 2t

    on player left clicks block with:air:
        - ratelimit <player> 0.4s
        - if <context.location.block.material.name||air> == brewing_stand:
            - stop
        - if <player.has_flag[smoking]> && <player.flag[smoking]> >= 3:
            - flag <player> hand expire:2.3s
            - flag <player> smoking:-:3
            - spawn smoke_ring <player.eye_location.below[0.35].forward[0.45]>
            - spawn smoke_ring1 <player.eye_location.below[0.35].forward[0.45].face[<player.eye_location.below[0.35]>]>
            - foreach <player.eye_location.below[0.35].forward[0.45].find_entities.within[0.5].filter[has_flag[normal]].filter[has_flag[smoke_ring]]> as:ring:
                - run ring_task def:<[ring]>

ring_task:
    type: task
    definitions: proj
    debug: false
    script:
    - if <[proj].has_flag[reverse]>:
        - define step -0.1
    - else:
        - define step 0.1
    - if <player.location.pitch> <= 0:
        - define gravity 0.0014

    - else:
        - define gravity -0.0077
    - define rot 0
    - define rot_scale <util.random.int[-9].to[9].div[1300]>
    - flag <[proj]> normal:!
    - while <[proj].is_spawned> && <[proj].flag[sneeze]> > 0:

        - teleport <[proj]> <[proj].location.forward[<[step]>].above[<[gravity]>]>
        - adjust <[proj]> right_rotation:0,0,<[proj].left_rotation.after[@].to_list.get[5].add[<[rot]>]>,1
        - define rot <[rot].add[<[rot_scale]>]>
        - flag <[proj]> sneeze:-:1
        - flag <[proj]> fly:+:1
        - if <[proj].flag[fly]> == 4:
            - adjust <[proj]> "text:<gray><&font[smoke]>1"
        - if <[proj].flag[fly]> == 7:
            - adjust <[proj]> "text:<gray><&font[smoke]>2"
        - if <[proj].flag[fly]> == 10:
            - adjust <[proj]> "text:<gray><&font[smoke]>3"
        - define scale <[proj].scale.add[0.06,0.06,0.06]>
        - adjust <[proj]> scale:<[scale]>
        - adjust <[proj]> opacity:<[proj].opacity.sub[6]>
        - if <[step]> < 0:
            - define step <[step].add[0.0005]>
        - else:
            - define step <[step].sub[0.0005]>
        - if <[proj].location.block.material.is_solid>:
            - playeffect at:<[proj].location> effect:poof data:0.02 offset:0.07,0.07,0.07 quantity:2
            - while stop
        - wait 2t
    - remove <[proj]>

smoke_ring:
    type: entity
    entity_type: text_display
    debug: false
    mechanisms:
        text: "<gray><&font[smoke]>0"
        text_shadowed: false
        background_color: 0,0,0,0
        scale: 2,2,2
        teleport_duration: 1
        opacity: 210
        translation: 0,-0.2,0
    flags:
        sneeze: 31
        smoke_ring: true
        normal: true

smoke_ring1:
    type: entity
    entity_type: text_display
    debug: false
    mechanisms:
        text: "<gray><&font[smoke]>0"
        text_shadowed: false
        background_color: 0,0,0,0
        scale: 2,2,2
        teleport_duration: 1
        translation: 0,-0.2,0
        opacity: 210
    flags:
        sneeze: 31
        smoke_ring: true
        reverse: true
        normal: true