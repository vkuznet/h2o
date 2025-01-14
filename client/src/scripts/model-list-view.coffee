Steam.ModelListView = (_) ->
  _predicate = node$ type: 'all'
  _items = do nodes$
  _hasItems = lift$ _items, (items) -> items.length > 0

  _canClearPredicate = lift$ _predicate, (predicate) -> predicate.type isnt 'all'
  _predicateCaption = lift$ _predicate, (predicate) ->
    switch predicate.type
      when 'all'
        'Showing\nall models'
      when 'compatibleWithFrame'
        "Showing models compatible with\n#{predicate.frameKey}"
      else
        ''

  #TODO ugly
  _isLive = node$ yes

  displayItem = (item) ->
    if item
      _.displayModel item.data
    else
      _.displayEmpty()

  displayActiveItem = ->
    displayItem find _items(), (item) -> item.isActive()

  activateAndDisplayItem = (item) ->
    for other in _items()
      if other is item
        other.isActive yes
      else
        other.isActive no

    displayItem item

  createItem = (model) ->
    #TODO replace with type checking
    console.assert isArray model.input_column_names
    console.assert has model, 'model_algorithm'
    console.assert has model, 'model_category'
    console.assert isObject model.critical_parameters
    console.assert isObject model.secondary_parameters
    console.assert isObject model.expert_parameters
    console.assert has model, 'response_column_name'
    console.assert has model, 'state'

    self =
      data: model
      title: model.key
      caption: model.model_category
      cutline: 'Response Column: ' + model.response_column_name
      display: -> activateAndDisplayItem self
      isActive: node$ no
      isSelected: node$ no

    apply$ _isLive, self.isSelected, (isLive, isSelected) ->
      _.modelSelectionChanged isSelected, self if isLive

    self

  displayModels = (models) ->
    _items items = map models, createItem
    activateAndDisplayItem head items

  apply$ _predicate, (predicate) ->
    console.assert isDefined predicate
    _.modelSelectionCleared()

    switch predicate.type
      when 'all'
        _.requestModelsAndCompatibleFrames (error, data) ->
          if error
            #TODO handle errors
          else
            displayModels data.models

      when 'compatibleWithFrame'
        #FIXME Need an api call to get "models and compatible frames for all models compatible with a frame"
        _.requestFrameAndCompatibleModels predicate.frameKey, (error, data) ->
          if error
            #TODO handle errors
          else
            compatibleModelsByKey = indexBy (head data.frames).compatible_models, (model) -> model.key
            _.requestModelsAndCompatibleFrames (error, data) ->
              if error
                #TODO handle errors
              else
                displayModels filter data.models, (model) -> if compatibleModelsByKey[model.key] then yes else no
    return
  
  deselectAllModels = ->
    #TODO ugly
    _isLive no
    for item in _items()
      item.isSelected no
    _isLive yes

  clearPredicate = ->
    deselectAllModels()
    _predicate type: 'all'

  link$ _.loadModels, (predicate) ->
    if predicate
      _predicate predicate
    else
      displayActiveItem()

  link$ _.deselectAllModels, deselectAllModels

  items: _items
  hasItems: _hasItems
  predicateCaption: _predicateCaption
  clearPredicate: clearPredicate
  canClearPredicate: _canClearPredicate
  template: 'model-list-view'

