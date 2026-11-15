# Gateway app icons (assets/connect-icons)

96 px PNGs, one per argus-connect app id, shown in Settings › Apps & connectors. They are the apps' own brand marks
(favicons / apple-touch icons from each service's site, Google product icons from gstatic, and a few from Wikimedia
Commons), used only to identify the service. Trademarks belong to their owners.

Adding an app: drop `<id>.png` (square, transparent or white background, ~96 px) here and add the id to
`ArgusAppsModel.BUNDLED`; `ArgusAppsModelTest.bundledIconsMatchTheAssets` keeps the two in step. Apps without a file get
a letter tile.
