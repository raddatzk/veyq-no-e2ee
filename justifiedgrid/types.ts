export interface PexelsPhoto {
  id: number;
  width: number;
  height: number;
  url: string;
  photographer: string;
  photographer_url: string;
  alt: string;
  src: {
    original: string;
    large2x: string;
    large: string;
    medium: string;
  };
}

export interface PexelsCuratedResponse {
  page: number;
  per_page: number;
  photos: PexelsPhoto[];
  total_results: number;
  next_page?: string;
}

export interface GalleryItem {
  id: number;
  width: number;
  height: number;
  aspectRatio: number;
  alt: string;
  imageUrl: string;
  photographer: string;
  photographerUrl: string;
  pageUrl: string;
}

export interface LayoutTile extends GalleryItem {
  left: number;
  top: number;
  renderedWidth: number;
  renderedHeight: number;
}

export interface LayoutResult {
  tiles: LayoutTile[];
  height: number;
}
